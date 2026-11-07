package ru.neurosteno.smoke

import java.io.File
import kotlinx.coroutines.runBlocking
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.EngineProviders
import ru.neurosteno.core.FileAudioInput
import ru.neurosteno.core.MemorySnapshot
import ru.neurosteno.core.PipelineOrchestrator
import ru.neurosteno.core.PipelineSettings
import ru.neurosteno.core.ResourcePlanner
import ru.neurosteno.core.TierConfig
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptJson
import ru.neurosteno.core.audio.WavDecoder
import ru.neurosteno.core.fragment.AudioSource
import ru.neurosteno.core.fragment.FragmentReprocessor
import ru.neurosteno.core.fragment.FragmentRequest
import ru.neurosteno.core.fragment.SpeakerMatcher
import ru.neurosteno.sherpa.ModelRoot
import ru.neurosteno.sherpa.SherpaAsr
import ru.neurosteno.sherpa.SherpaDiarizer
import ru.neurosteno.sherpa.SherpaLanguageId
import ru.neurosteno.sherpa.SherpaVad

private class Env(val cfg: TierConfig, val root: ModelRoot, val threads: Int, val settings: PipelineSettings, val providers: EngineProviders)

private fun env(cfgPath: String, rootPath: String): Env {
    val cfg = TierConfig.parse(File(cfgPath).readText())
    val root = ModelRoot(File(rootPath))
    val plan = ResourcePlanner.plan(MemorySnapshot(16_384, 8_000), cfg, Runtime.getRuntime().availableProcessors())
    val threads = System.getenv("PARITY_THREADS")?.toIntOrNull() ?: plan.numThreads
    val hasLid = cfg.language.effectiveMode == "auto" && cfg.language.lidModel != null && cfg.asrEn != null &&
        File(root.dir, cfg.language.lidModel!!).isDirectory
    val providers = EngineProviders(
        vad = { SherpaVad(cfg.vad, root, 1) },
        asr = { lang ->
            SherpaAsr(if (lang == "en") (cfg.asrEn ?: cfg.asr) else cfg.asr, root, threads, lang,
                keepPunct = cfg.punctuation.source == "asr_native",
                dropTinyMaxChars = cfg.postprocess.dropTinyChunks.takeIf { it.enabled }?.maxChars)
        },
        diarizer = { SherpaDiarizer(cfg.diarization, root, threads) },
        languageId = if (hasLid) ({ SherpaLanguageId(cfg.language.lidModel!!, root, 1, cfg.language.lidSeconds) }) else null,
    )
    return Env(cfg, root, threads, PipelineSettings.from(cfg, "jvm-smoke", plan), providers)
}

private fun fmt(ms: Long) = "%d.%03d".format(ms / 1000, ms % 1000)

private fun printUtts(t: Transcript, marks: Set<Int> = emptySet(), prefix: String = "") {
    t.utterances.forEachIndexed { i, u ->
        println("$prefix${if (i in marks) "*" else " "}[${"%2d".format(i)}] ${fmt(u.startMs)}-${fmt(u.endMs)} ${u.speakerId}: ${u.text}")
    }
}

/**
 * `--reprocess <wav> <cfg.json> <models-root> [--scenario "utt=5 [utt2=7] lang=ru|en|auto speakers=N recompute=true|false pad=300 from=ms to=ms"]...`
 * Полная обработка, затем повторная обработка фрагмента по каждому сценарию (каждый -- от исходной полной расшифровки).
 */
fun runReprocess(args: List<String>) {
    val wav = File(args[0])
    val e = env(args[1], args[2])
    println("tier=${e.cfg.tier} threads=${e.threads} wav=${wav.name}")
    val scenarios = args.drop(3).chunked(2).filter { it[0] == "--scenario" }.map { it[1] }

    // 1) полная обработка
    val orchestrator = PipelineOrchestrator(
        decoder = object : ru.neurosteno.core.AudioDecoder {
            override fun decode(input: ru.neurosteno.core.AudioInput, progress: ru.neurosteno.core.ProgressListener): AudioBuffer =
                WavDecoder.decode(File((input as FileAudioInput).path))
        },
        engines = e.providers,
        settings = e.settings,
    )
    val full = runBlocking { orchestrator.run(FileAudioInput(wav.path)) }
    val base = full.transcript
    println("FULL: duration=${base.meta.audioDurationMs} ms processing=${base.meta.processingMs} ms RTF=${"%.3f".format(base.meta.rtf)} lang=${base.meta.language} utterances=${base.utterances.size}")
    println("FULL stage_ms=${base.meta.stageMs}")
    printUtts(base)

    val source = AudioSource.wav(wav)
    for (sc in scenarios) {
        val kv = sc.trim().split(Regex("\\s+")).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val idx = kv.filterKeys { it.startsWith("utt") }.values.flatMap { it.split(',') }.mapNotNull { it.toIntOrNull() }
        val from = kv["from"]?.toLong() ?: idx.minOf { base.utterances[it].startMs }
        val to = kv["to"]?.toLong() ?: idx.maxOf { base.utterances[it].endMs }
        val req = FragmentRequest(
            startMs = from, endMs = to,
            language = kv["lang"],
            numSpeakers = kv["speakers"]?.toIntOrNull(),
            recomputeSpeakers = kv["recompute"]?.toBoolean() ?: false,
        )
        val tuning = kv["pad"]?.toLong()?.let { ru.neurosteno.core.fragment.FragmentTuning(paddingMs = it) } ?: ru.neurosteno.core.fragment.FragmentTuning()
        println("\n=== SCENARIO: $sc  -> request $from..$to ms ===")
        val rp = FragmentReprocessor(e.providers, e.settings, tuning)
        var last = ""
        val r = runBlocking {
            rp.reprocess(source, base, req) { stage, _, overall ->
                if (stage.name != last) { last = stage.name; print("  -> $stage (${(overall * 100).toInt()}%)") }
            }
        }
        println()
        println("outcome=${r.outcome} \"${r.message}\" range=${r.range} language=${r.language} reprocess_ms=${r.processingMs} stage_ms=${r.stageMs}")
        println("assignments=" + r.speakerAssignments.joinToString { "local${it.localSpeaker}->${it.speakerId}(${it.method}${it.score?.let { s -> ",%.2f".format(s) } ?: ""})" })
        println("speakerRemap=${r.speakerRemap}")
        r.warnings.forEach { println("WARNING: $it") }
        println("BEFORE (removed ${r.removed.size}):")
        r.removed.forEach { u -> println("   ${fmt(u.startMs)}-${fmt(u.endMs)} ${u.speakerId}: ${u.text}") }
        println("AFTER (added ${r.added.size}):")
        r.added.forEach { u -> println("   ${fmt(u.startMs)}-${fmt(u.endMs)} ${u.speakerId}: ${u.text}") }
        println("transcript size ${base.utterances.size} -> ${r.transcript.utterances.size}; json valid=" +
            runCatching { TranscriptJson.parse(r.json).size == r.transcript.utterances.size }.getOrDefault(false))
    }
}

/**
 * `--emb-check <cfg.json> <models-root> <ref.json> <audio.wav> [...]`: эмбеддинги реплик по эталонной разметке датасета;
 * косинусная близость для пар «тот же / другой говорящий» -- для подбора [SpeakerMatcher.DEFAULT_THRESHOLD].
 * Аргументы после root -- тройки/пары `ref.json audio.wav`.
 */
fun runEmbCheck(args: List<String>) {
    val e = env(args[0], args[1])
    val same = ArrayList<Double>()
    val diff = ArrayList<Double>()
    // пары (ref.json, audio.wav)
    for ((refPath, wavPath) in args.drop(2).chunked(2)) {
        val fs = ArrayList<Double>()
        val fd = ArrayList<Double>()
        val items = TranscriptJson.parse(File(refPath).readText()).filter { it.endMs - it.startMs >= 2_000 }
        val audio = WavDecoder.decode(File(wavPath))
        val src = AudioSource.of(audio)
        val d = e.providers.diarizer()
        val clips = items.map { src.read(it.startMs, minOf(it.endMs, it.startMs + 8_000)) }
        val emb = d.speakerEmbeddings(clips)
        d.close()
        for (i in items.indices) for (j in i + 1 until items.size) {
            val a = emb[i] ?: continue
            val b = emb[j] ?: continue
            // реплики с пересечением по времени -- пропускаем (загрязнены другим голосом)
            if (items[i].startMs < items[j].endMs && items[j].startMs < items[i].endMs) continue
            val c = SpeakerMatcher.cosine(a, b)
            if (items[i].speakerId == items[j].speakerId) { same += c; fs += c } else { diff += c; fd += c }
        }
        println("${File(wavPath).name}: utterances=${items.size} dim=${emb.firstOrNull { it != null }?.size} same[min=%.2f med=%.2f] diff[med=%.2f max=%.2f]".format(
            fs.minOrNull() ?: Double.NaN, fs.sorted().getOrElse(fs.size / 2) { Double.NaN }, fd.sorted().getOrElse(fd.size / 2) { Double.NaN }, fd.maxOrNull() ?: Double.NaN))
    }
    fun stats(n: String, x: List<Double>) {
        val s = x.sorted()
        println("$n: n=${s.size} min=%.3f p5=%.3f p25=%.3f median=%.3f p75=%.3f p95=%.3f max=%.3f".format(
            s.first(), s[(s.size * 0.05).toInt()], s[(s.size * 0.25).toInt()], s[s.size / 2], s[(s.size * 0.75).toInt()], s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)], s.last()))
    }
    stats("same speaker", same)
    stats("different speaker", diff)
    for (t in listOf(0.3, 0.4, 0.5, 0.6, 0.7)) {
        println("threshold %.1f: same accepted %.1f%%, different accepted (false match) %.1f%%".format(
            t, 100.0 * same.count { it >= t } / same.size, 100.0 * diff.count { it >= t } / diff.size))
    }
}
