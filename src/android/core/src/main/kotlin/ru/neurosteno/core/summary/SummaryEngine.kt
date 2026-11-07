package ru.neurosteno.core.summary

import ru.neurosteno.core.LlmAssistant
import ru.neurosteno.core.Utterance

/**
 * LLM с ограничением генерации грамматикой GBNF и повторным использованием KV-кэша префикса (общий системный промпт между
 * фрагментами; без него время выжимки ×1.7). Реализация -- `ru.neurosteno.llm.LlamaAssistant`.
 */
interface ConstrainedLlm : LlmAssistant {
    /**
     * Жадная генерация; если [grammar] не null -- вывод строго по GBNF (корневое правило `root`). [reusePrefix] -- не пересчитывать
     * общий с предыдущим вызовом префикс промпта.
     */
    fun generateConstrained(prompt: String, maxTokens: Int, grammar: String?, reusePrefix: Boolean, onToken: (String) -> Unit = {}): String
}

/** Системные промпты (`assets/prompts/summary_system.txt`, `summary_overview_system.txt`). */
data class SummaryPrompts(val system: String, val overviewSystem: String)

/** Один вызов LLM (для логов `llm_done` и оценки времени). */
data class SummaryCall(val promptChars: Int, val generatedPieces: Int, val ms: Long, val parsed: Boolean)

class SummaryResult(
    val doc: SummaryDoc,
    val stats: SummaryStats,
    val calls: List<SummaryCall>,
    /** Тем до склейки соседних. */
    val rawTopics: Int,
)

/**
 * Выжимка записи (B50): фрагменты по долинам косинуса -> один вызов LLM на фрагмент (JSON по GBNF) -> проверка ссылок -> склейка
 * соседних тем -> JSON для UI. Порт `bench_rag/summary.py`; `extractive` -- без LLM.
 */
class SummaryEngine(private val cfg: SummaryConfig = SummaryConfig(), private val prompts: SummaryPrompts? = null) {

    /** Шаги для UI/уведомления: [done] из [total] фрагментов; [stage] -- `segment` | `fragment` | `overview`. */
    fun interface Progress { fun onProgress(stage: String, done: Int, total: Int) }

    fun toSUtts(src: List<Utterance>): List<SUtt> = src.mapIndexed { i, u ->
        SUtt(i, u.speakerId, u.speakerId.substringAfter('_', "0").toIntOrNull() ?: 0, u.startMs, u.endMs, u.text)
    }

    /**
     * Векторы реплик для долин: эмбеддер (если есть) либо лексический TF-IDF. Второй элемент -- окно k (для лексики 4).
     */
    fun vectors(utts: List<SUtt>, embedder: ru.neurosteno.core.search.TextEmbedder?): Pair<List<DoubleArray>, Int> {
        if (embedder != null && utts.isNotEmpty()) {
            val v = embedder.embed(utts.map { it.text }, false)
            if (v.size == utts.size) return v.map { f -> DoubleArray(f.size) { f[it].toDouble() } } to cfg.segmenter.k
        }
        return TopicSegmenter.lexicalVectors(utts.map { it.text }) to SegmenterConfig.LEXICAL_K
    }

    /** Мгновенная выжимка без LLM. */
    fun extractive(src: List<Utterance>, embedder: ru.neurosteno.core.search.TextEmbedder? = null): SummaryDoc {
        val utts = toSUtts(src)
        if (utts.isEmpty()) return SummaryDoc(mode = "extractive")
        val (vecs, k) = vectors(utts, embedder)
        return toDoc(utts, ExtractiveSummary.digest(utts, vecs, cfg.fragmentChars, cfg.segmenter, k), mode = "extractive")
    }

    /**
     * LLM-выжимка. [onPartial] вызывается после каждого фрагмента с документом из готовых тем (`done = false`).
     * Отмена -- прерыванием потока (как у остальных вызовов LLM): между фрагментами проверяется флаг.
     */
    fun summarize(
        src: List<Utterance>, embedder: ru.neurosteno.core.search.TextEmbedder?, llm: ConstrainedLlm, model: String = "",
        progress: Progress = Progress { _, _, _ -> }, onPartial: (SummaryDoc) -> Unit = {},
    ): SummaryResult {
        val p = checkNotNull(prompts) { "нет системных промптов" }
        val utts = toSUtts(src)
        val st = SummaryStats()
        if (utts.isEmpty()) return SummaryResult(SummaryDoc(mode = "llm", model = model), st, emptyList(), 0)
        progress.onProgress("segment", 0, 0)
        val (vecs, k) = vectors(utts, embedder)
        val frags = SummaryPlanner.plan(utts, vecs, cfg.fragmentChars, cfg.segmenter, k)
        val validator = SummaryValidator(utts)
        val topics = ArrayList<Topic>()
        val calls = ArrayList<SummaryCall>()
        for ((fi, f) in frags.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("выжимка прервана")
            progress.onProgress("fragment", fi, frags.size)
            val (lo, hi) = f
            val body = (lo until hi).joinToString("\n") { SummaryPlanner.line(utts[it], it - lo + 1) }
            val prompt = SummaryPlanner.chatml(p.system, body)
            val t0 = System.nanoTime()
            var pieces = 0
            val text = llm.generateConstrained(prompt, cfg.maxNewTokens, SummaryPlanner.grammar(utts, lo, hi), true) { pieces++ }
            val d = SummaryValidator.parse(SummaryPlanner.extractJson(text), st)
            calls += SummaryCall(prompt.length, pieces, (System.nanoTime() - t0) / 1_000_000, d != null)
            val t = if (d != null) validator.validate(d, lo, hi, st, cfg.minSupportPoint, true, cfg.minSupportItem) else null
            if (t != null) {
                t.centroid = centroid(vecs, lo, hi)
                topics += t
                onPartial(toDoc(utts, SummaryMerger.merge(topics, cfg.mergeTitleSim), "llm", model, done = false, fragments = frags.size, fragmentsDone = fi + 1))
            }
        }
        val merged = SummaryMerger.merge(topics, cfg.mergeTitleSim)
        var overview = ""
        if (cfg.overview && merged.isNotEmpty()) {
            progress.onProgress("overview", frags.size, frags.size)
            overview = makeOverview(llm, p.overviewSystem, merged)
        }
        val doc = toDoc(utts, merged, "llm", model, overview = overview, fragments = frags.size, fragmentsDone = frags.size)
        return SummaryResult(doc, st, calls, topics.size)
    }

    private fun centroid(vecs: List<DoubleArray>, lo: Int, hi: Int): DoubleArray {
        val m = DoubleArray(vecs[0].size)
        for (i in lo until hi) for (j in m.indices) m[j] += vecs[i][j]
        for (j in m.indices) m[j] /= (hi - lo).toDouble()
        return m
    }

    fun makeOverview(llm: ConstrainedLlm, system: String, topics: List<Topic>): String {
        val body = topics.joinToString("\n") { t ->
            "- ${t.title}: " + (t.decisions.map { it.text } + t.points.map { it.text }).take(3).joinToString("; ")
        }.take(2200)
        val out = llm.generateConstrained(SummaryPlanner.chatml(system, body), 120, null, true)
        return out.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        /** Топики -> документ для UI (время и номера -- из реплик). Эталон `to_json`. */
        fun toDoc(
            utts: List<SUtt>, topics: List<Topic>, mode: String, model: String = "", overview: String = "", done: Boolean = true,
            fragments: Int = 0, fragmentsDone: Int = 0,
        ): SummaryDoc {
            fun ref(i: Int) = SummaryRef(i + 1, utts[i].startMs, utts[i].speakerId)
            return SummaryDoc(
                mode = mode, model = model, done = done, fragments = fragments, fragmentsDone = fragmentsDone, overview = overview,
                topics = topics.map { t ->
                    SummaryTopic(
                        t.title, utts[t.start].startMs, utts[t.end].endMs,
                        t.points.map { SummaryPoint("speaker_${it.speaker}", it.text, it.refs.map(::ref)) },
                        t.decisions.map { SummaryDecision(it.text, it.refs.map(::ref)) },
                        t.tasks.map { SummaryTask(it.who, it.text, it.refs.map(::ref)) },
                    )
                },
            )
        }
    }
}
