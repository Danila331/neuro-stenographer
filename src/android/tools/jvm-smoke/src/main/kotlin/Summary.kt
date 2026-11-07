package ru.neurosteno.smoke

import java.io.File
import java.util.Locale
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.TranscriptJson
import ru.neurosteno.core.summary.ConstrainedLlm
import ru.neurosteno.core.summary.SegmenterConfig
import ru.neurosteno.core.summary.SummaryConfig
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryEngine
import ru.neurosteno.core.summary.SummaryPlanner
import ru.neurosteno.core.summary.SummaryPrompts
import ru.neurosteno.core.summary.SummaryValidator
import ru.neurosteno.core.summary.SUtt
import ru.neurosteno.llm.LlamaAssistant
import ru.neurosteno.llm.LlamaOptions

private val flags = LlamaOptions(noRepack = true, flashAttn = true, kvCacheType = "q8_0", useMmap = true) // как llm.* medium

private fun load(model: String, nCtx: Int = 4096) = LlamaAssistant(File(model), nCtx, 4, options = flags)

private fun utterances(file: String): List<Utterance> =
    TranscriptJson.parse(File(file).readText(Charsets.UTF_8)).map { Utterance(it.speakerId, it.startMs, it.endMs, it.text, emptyList()) }

/**
 * `--grammar-check <model.gguf>`: GBNF в JNI и KV-кэш префикса на реальной модели.
 * 1) игрушечная грамматика даёт только допустимые слова; 2) грамматика фрагмента даёт разбираемый JSON с существующими ссылками;
 * 3) повторное использование префикса даёт побайтно тот же вывод, что и пересчёт, и быстрее.
 */
fun runGrammarCheck(args: List<String>) {
    val llm = load(args[0])
    var failed = 0
    fun check(ok: Boolean, what: String) { println((if (ok) "OK   " else "FAIL ") + what); if (!ok) failed++ }
    val yn = llm.generateConstrained(SummaryPlanner.chatml("Отвечай одним словом.", "Земля круглая? Ответь да или нет."), 8, "root ::= \"да\" | \"нет\"\n", false)
    check(yn == "да" || yn == "нет", "игрушечная грамматика: '$yn'")

    val dialog = listOf(
        "Давайте обсудим бюджет на рыбу за квартал.", "В июле потратили восемь тысяч четыреста рублей.", "А в августе одиннадцать тысяч двести.",
        "Почему так выросло?", "Был праздник, купили сёмгу.", "Предлагаю сократить закупку сёмги вдвое.", "Это невозможно, коллеги привыкли.",
        "Я подготовлю другой расчёт к пятнице.",
    )
    val utts = dialog.mapIndexed { i, t -> SUtt(i, "speaker_${1 + i % 2}", 1 + i % 2, i * 4000L, i * 4000L + 3000, t) }
    val system = File(args[1], "summary_system.txt").readText().trim()
    val body = utts.indices.joinToString("\n") { SummaryPlanner.line(utts[it], it + 1) }
    val prompt = SummaryPlanner.chatml(system, body)
    val grammar = SummaryPlanner.grammar(utts, 0, utts.size)
    val fresh1 = timed { llm.generateConstrained(prompt, 400, grammar, false) }
    val st = ru.neurosteno.core.summary.SummaryStats()
    val parsed = SummaryValidator.parse(fresh1.first, st)
    check(parsed != null, "вывод по грамматике разобран как JSON (${fresh1.first.length} симв., ${fresh1.second} мс)")
    val v = parsed?.let { SummaryValidator(utts).validate(it, 0, utts.size, st) }
    check(v != null && st.refsBadRange == 0 && st.refsBadSpeaker == 0, "ссылки: bad_range=${st.refsBadRange} bad_speaker=${st.refsBadSpeaker}, пунктов ${v?.points?.size}")

    // префикс: тот же системный промпт, другой фрагмент
    val body2 = utts.indices.drop(2).joinToString("\n") { SummaryPlanner.line(utts[it], it - 1) }
    val prompt2 = SummaryPlanner.chatml(system, body2)
    val g2 = SummaryPlanner.grammar(utts, 2, utts.size)
    llm.generateConstrained(prompt, 1, grammar, false) // прогрев: в кэше -- prompt
    val reused = timed { llm.generateConstrained(prompt2, 400, g2, true) }
    val recomputed = timed { llm.generateConstrained(prompt2, 400, g2, false) }
    check(reused.first == recomputed.first, "префикс из кэша даёт тот же вывод, что и полный пересчёт")
    println("время: с кэшем префикса ${reused.second} мс, без ${recomputed.second} мс")
    llm.close()
    if (failed > 0) exitProcess(1)
}

private fun <T> timed(f: () -> T): Pair<T, Long> {
    val t = System.nanoTime()
    val r = f()
    return r to (System.nanoTime() - t) / 1_000_000
}

/**
 * `--summary <transcript.json> <model.gguf> <prompts_dir> [out.json] [no-reuse] [overview]`: выжимка Kotlin-кодом на реальной модели.
 * Печатает фрагменты, время каждого вызова, темы и счётчики проверки; пишет `summary.json`. Лексические векторы (эмбеддера на хосте нет).
 */
fun runSummary(args: List<String>) {
    val utts = utterances(args[0])
    val dir = File(args[2])
    val prompts = SummaryPrompts(File(dir, "summary_system.txt").readText().trim(), File(dir, "summary_overview_system.txt").readText().trim())
    val reuse = "no-reuse" !in args
    val cfg = SummaryConfig(overview = "overview" in args, segmenter = SegmenterConfig())
    val engine = SummaryEngine(cfg, prompts)
    val t0 = System.nanoTime()
    val ex = engine.extractive(utts)
    println("extractive: ${ex.topics.size} тем за ${(System.nanoTime() - t0) / 1_000_000} мс")
    val raw = load(args[1])
    val llm = object : ConstrainedLlm by raw {
        var n = 0
        override fun generateConstrained(prompt: String, maxTokens: Int, grammar: String?, reusePrefix: Boolean, onToken: (String) -> Unit): String {
            val (r, ms) = timed { raw.generateConstrained(prompt, maxTokens, grammar, reusePrefix && reuse, onToken) }
            println(String.format(Locale.ROOT, "  вызов %d: prompt %d симв., ответ %d симв., %d мс", ++n, prompt.length, r.length, ms))
            return r
        }
    }
    val t1 = System.nanoTime()
    val res = engine.summarize(utts, null, llm, File(args[1]).name)
    val ms = (System.nanoTime() - t1) / 1_000_000
    println("llm: ${res.doc.topics.size} тем (до склейки ${res.rawTopics}), фрагментов ${res.doc.fragments}, $ms мс, ${res.stats}")
    for (t in res.doc.topics) {
        println("# ${t.title} [${t.startMs / 1000}-${t.endMs / 1000} с]")
        t.points.forEach { println("   ${it.speaker}: ${it.text} ${it.refs.map { r -> r.n }}") }
        t.decisions.forEach { println("   РЕШЕНИЕ: ${it.text}") }
        t.tasks.forEach { println("   ЗАДАЧА: ${it.who} ${it.text}") }
    }
    if (res.doc.overview.isNotBlank()) println("overview: ${res.doc.overview}")
    args.getOrNull(3)?.takeIf { it.endsWith(".json") }?.let {
        File(it).writeText(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(SummaryDoc.serializer(), res.doc), Charsets.UTF_8)
        println("записано: $it")
    }
    raw.close()
}
