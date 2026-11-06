package ru.neurosteno.smoke

import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.AudioDecoder
import ru.neurosteno.core.AudioInput
import ru.neurosteno.core.EngineProviders
import ru.neurosteno.core.FileAudioInput
import ru.neurosteno.core.MemorySnapshot
import ru.neurosteno.core.PipelineOrchestrator
import ru.neurosteno.core.PipelineSettings
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.ResourcePlanner
import ru.neurosteno.core.TierConfig
import ru.neurosteno.core.llm.LlmPunctuator
import ru.neurosteno.llm.LlamaAssistant
import ru.neurosteno.core.audio.WavDecoder
import ru.neurosteno.sherpa.ModelRoot
import ru.neurosteno.sherpa.SherpaAsr
import ru.neurosteno.sherpa.SherpaDiarizer
import ru.neurosteno.sherpa.SherpaVad

/** WAV-декодер для хоста (на Android -- MediaCodecAudioDecoder). */
private class WavFileDecoder : AudioDecoder {
    override fun decode(input: AudioInput, progress: ProgressListener): AudioBuffer =
        WavDecoder.decode(File((input as FileAudioInput).path))
}

/** args: <wav> <tier.json> <models-root> [out.json] */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--llm-raw") { // --llm-raw <model.gguf> <вопрос...>: ChatML без правок
        val llm = LlamaAssistant(File(args[1]), 2048, 4)
        val q = args.drop(2).joinToString(" ")
        println("raw: " + llm.generate("<|im_start|>user\n$q<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", 120))
        llm.close()
        return
    }
    if (args.firstOrNull() == "--llm") { // --llm <model.gguf> <текст...>: проверка LLM-пунктуации на коротком тексте
        val llm = LlamaAssistant(File(args[1]), 2048, 4)
        val p = LlmPunctuator(llm, 10_000)
        val text = args.drop(2).joinToString(" ")
        val u = ru.neurosteno.core.Utterance("speaker_1", 0, 1000, text, emptyList())
        val t0 = System.nanoTime()
        val r = p.punctuate(listOf(u)) { _, _ -> }
        println("in : $text\nout: ${r[0].text}\n(${(System.nanoTime() - t0) / 1_000_000} ms)")
        println("raw: " + llm.generate(p.buildPrompt(listOf(text)), 200))
        llm.close()
        return
    }
    if (args.size < 3) {
        System.err.println("usage: jvm-smoke <audio.wav> <config/tiers/X.json> <models-root> [out.json]")
        exitProcess(2)
    }
    val wav = File(args[0])
    val cfgText = File(args[1]).readText()
    val cfg = TierConfig.parse(cfgText)
    val root = ModelRoot(File(args[2]))
    val plan = ResourcePlanner.plan(MemorySnapshot(16_384, 8_000), cfg, Runtime.getRuntime().availableProcessors())
    val threads = plan.numThreads
    println("tier=${cfg.tier} threads=$threads config_hash=${TierConfig.hash(cfgText)} wav=${wav.name}")

    // LLM-пунктуация: SMOKE_LLM_GGUF=/путь/модель.gguf (нужна libneurosteno_llm: scripts/build_host_jni.sh)
    val gguf = System.getenv("SMOKE_LLM_GGUF")?.let(::File)
    val punct: ((Long) -> ru.neurosteno.core.Punctuator)? = gguf?.let { f ->
        { durationMs: Long ->
            val llm = LlamaAssistant(f, cfg.llm.nCtx, threads)
            val shown = if (System.getenv("SMOKE_DEBUG_LLM") == null) llm else object : ru.neurosteno.core.LlmAssistant by llm {
                override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String =
                    llm.generate(prompt, maxTokens, onToken).also { println("LLM prompt=<<${prompt.takeLast(300)}>>\nLLM answer=<<$it>>") }
            }
            LlmPunctuator(shown, durationMs, cfg.llm.timeBudgetRtf)
        }
    }
    val providers = EngineProviders(
        vad = { SherpaVad(cfg.vad, root, 1) },
        punctuator = punct,
        asr = { lang ->
            SherpaAsr(if (lang == "en") (cfg.asrEn ?: cfg.asr) else cfg.asr, root, threads, lang,
                keepPunct = cfg.punctuation.source == "asr_native",
                onRawResult = if (System.getenv("SMOKE_DEBUG_ASR") != null) { text, tokens, ts ->
                    println("RAW text=\"$text\"\n    tokens=$tokens\n    ts=${ts.toList()}")
                } else null)
        },
        diarizer = {
            val inner = SherpaDiarizer(cfg.diarization, root, threads)
            object : ru.neurosteno.core.Diarizer by inner {
                override fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener) =
                    inner.diarize(audio, numSpeakers, progress).also { t ->
                        println("DIAR turns=${t.size} speakers=${t.map { it.speaker }.distinct()} first=${t.take(4)}")
                    }
            }
        },
    )
    val orchestrator = PipelineOrchestrator(WavFileDecoder(), providers, PipelineSettings.from(cfg, "jvm-smoke", plan))
    var lastStage = ""
    val result = runBlocking {
        orchestrator.run(FileAudioInput(wav.path)) { stage, _, overall ->
            if (stage.name != lastStage) { lastStage = stage.name; println("  -> $stage (${(overall * 100).toInt()}%)") }
        }
    }
    val meta = result.transcript.meta
    println("duration=${meta.audioDurationMs} ms processing=${meta.processingMs} ms RTF=${"%.3f".format(meta.rtf)} language=${meta.language}")
    println("stage_ms=${meta.stageMs}")
    result.warnings.forEach { println("WARNING: $it") }
    println(result.json)
    args.getOrNull(3)?.let { File(it).writeText(result.json) }
}
