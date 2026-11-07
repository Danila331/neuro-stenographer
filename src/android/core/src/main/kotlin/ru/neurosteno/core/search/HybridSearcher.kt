package ru.neurosteno.core.search

/**
 * Гибридный поиск (порт `bench_rag/retrieval.py`, `Index.search`): BM25 по основам + плотные векторы, слияние RRF
 * (k = 60, веса 0.5/0.5, нулевой BM25 не голосует), ограничение по говорящему из вопроса -- `soft` (+0.15 диапазона оценок
 * окнам нужного говорящего) или `hard` (фильтр; если никто не подходит -- список без фильтра). Область: запись или вся история.
 *
 * Плотные оценки берутся у [vectors] как `denseCandidates` ближайших окон области; остальные окна в слиянии не голосуют
 * (при `denseCandidates` >= размера области результат совпадает с полным перебором эталона).
 */
class HybridSearcher(
    private val chunks: List<Chunk>,
    private val bm25: Bm25Index,
    /** Имена говорящих записи (`speaker_N` -> имя): метаданные, читаются в момент запроса (переименование не требует переиндексации). */
    private val namesOf: (recordId: String) -> Map<String, String>,
    private val vectors: VectorStore? = null,
    private val cfg: SearchConfig = SearchConfig(),
    private val denseCandidates: Int = DEFAULT_DENSE_CANDIDATES,
) {
    init {
        require(bm25.size == chunks.size) { "в BM25 ${bm25.size} окон != ${chunks.size}" }
    }

    constructor(
        chunks: List<Chunk>, docTerms: List<List<String>>, records: Map<String, SearchRecord>, vectors: VectorStore? = null,
        cfg: SearchConfig = SearchConfig(), denseCandidates: Int = DEFAULT_DENSE_CANDIDATES,
    ) : this(chunks, Bm25Index(docTerms, cfg.bm25.k1, cfg.bm25.b, cfg.bm25.stemmer), { records[it]?.names ?: emptyMap() }, vectors, cfg, denseCandidates)

    private val allNames: Collection<String> by lazy { chunks.map { it.recordId }.distinct().flatMap { namesOf(it).values }.toSet() }

    class Result(val hits: List<Hit>, val speaker: SpeakerQuery, val terms: List<String>)

    /** [qvec] -- вектор запроса (null -- только BM25). [speakerMode] -- `soft` | `hard` | `off`; по умолчанию из конфига. */
    fun search(
        query: String,
        qvec: FloatArray?,
        k: Int = cfg.topK,
        recordId: String? = null,
        speakerMode: String = cfg.speakerFilter,
        useBm25: Boolean = true,
        useDense: Boolean = true,
        softBoost: Double = SOFT_BOOST,
    ): Result {
        val scopeNames = if (recordId != null) namesOf(recordId).values else allNames
        val sq = if (speakerMode != "off") SpeakerQueryParser.parse(query, scopeNames) else SpeakerQuery(query)
        val q = sq.clean.ifEmpty { query }
        val idx = scope(recordId, sq, speakerMode)
        val n = idx.size
        val scB = if (useBm25) bm25.scores(q).let { all -> FloatArray(n) { all[idx[it]] } } else null
        val scD = if (useDense && vectors != null && qvec != null) dense(qvec, idx, recordId) else null
        var s: FloatArray = FloatArray(n)
        if (scB != null && scD != null) {
            val rrf = cfg.fusion.k
            val a = cfg.fusion.denseWeight
            addRrf(s, scB, 1 - a, rrf, positiveOnly = true)
            addRrf(s, scD.scores, a, rrf, positiveOnly = false, present = scD.present)
        } else if (scD != null) {
            s = FloatArray(n) { if (scD.present[it]) scD.scores[it] else -Float.MAX_VALUE }
        } else if (scB != null) {
            s = scB
        }
        val boost = if (speakerMode == "soft" && sq.active) softBoost else 0.0
        val fin = DoubleArray(n) { s[it].toDouble() }
        if (boost != 0.0 && n > 0) {
            var mx = s[0]
            var mn = s[0]
            for (x in s) { if (x > mx) mx = x; if (x < mn) mn = x }
            val range = mx.toDouble() - mn.toDouble() + 1e-6
            for (i in 0 until n) {
                val c = chunks[idx[i]]
                val m = c.speakers.any { SpeakerQueryParser.matches(sq, it, namesOf(c.recordId)) }
                fin[i] += boost * (if (m) 1.0 else 0.0) * range
            }
        }
        val order = (0 until n).sortedWith(compareByDescending<Int> { fin[it] }.thenBy { it }).take(k)
        return Result(order.map { Hit(chunks[idx[it]], fin[it]) }, sq, bm25.queryTerms(q))
    }

    private fun scope(recordId: String?, sq: SpeakerQuery, speakerMode: String): IntArray {
        val base = chunks.indices.filter { recordId == null || chunks[it].recordId == recordId }
        if (speakerMode == "hard" && sq.active) {
            val sm = base.filter { i ->
                val c = chunks[i]
                c.speakers.any { SpeakerQueryParser.matches(sq, it, namesOf(c.recordId)) }
            }
            if (sm.isNotEmpty()) return sm.toIntArray() // пусто (диаризация не сходится с вопросом) -> список без фильтра
        }
        return base.toIntArray()
    }

    private class Dense(val scores: FloatArray, val present: BooleanArray)

    private fun dense(qvec: FloatArray, idx: IntArray, recordId: String?): Dense {
        // область хранилища -- вся запись (или история): hard-фильтр применяется к найденным, поэтому берём кандидатов по всей области
        val limit = minOf(denseCandidates, chunks.count { recordId == null || it.recordId == recordId })
        val found = vectors!!.search(qvec, limit, recordId).toMap()
        val scores = FloatArray(idx.size)
        val present = BooleanArray(idx.size)
        for (i in idx.indices) {
            val sc = found[chunks[idx[i]].id]
            if (sc != null) { scores[i] = sc; present[i] = true }
        }
        return Dense(scores, present)
    }

    /** `s += where(pos, 2 * w / (rrf_k + rank + 1), 0)` в float32 (ранг -- место по убыванию оценки, равные -- по порядку окон). */
    private fun addRrf(s: FloatArray, sc: FloatArray, w: Double, rrfK: Int, positiveOnly: Boolean, present: BooleanArray? = null) {
        val n = sc.size
        val order = (0 until n).sortedWith(compareByDescending<Int> { if (present != null && !present[it]) -Float.MAX_VALUE else sc[it] }.thenBy { it })
        val num = (2 * w).toFloat()
        for ((rank, i) in order.withIndex()) {
            val pos = if (positiveOnly) sc[i] > 0f else present?.get(i) != false
            if (pos) s[i] += num / ((rrfK.toFloat() + rank.toFloat()) + 1f)
        }
    }

    companion object {
        const val SOFT_BOOST = 0.15
        const val DEFAULT_DENSE_CANDIDATES = 400
    }
}
