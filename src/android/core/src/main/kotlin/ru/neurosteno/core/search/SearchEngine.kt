package ru.neurosteno.core.search

/** Итог индексации одной записи. */
data class IndexReport(val chunks: Int, val embedded: Boolean, val ms: Long)

/**
 * Движок AI-поиска (B51): индексация записей и гибридный поиск по записи или по всей истории.
 *
 * Индексация (фон, после сохранения расшифровки): окна и основы сразу в [chunks] (BM25 доступен немедленно), затем эмбеддинги окон
 * в [vectors] -- эмбеддер получается через [embedder] только на время расчёта. Если эмбеддера нет или он упал, запись остаётся
 * доступной для BM25 (`hasVectors == false`), [reindexMissing] достроит векторы позже. Удаление записи удаляет окна и векторы.
 * Embedder, LLM и ASR никогда не в памяти одновременно: [embedder] вызывается движком только внутри [index] и [search].
 */
class SearchEngine(
    private val cfg: SearchConfig,
    private val chunks: ChunkStore,
    private val vectors: VectorStore,
    private val embedder: () -> TextEmbedder?,
    /** Имена говорящих записи (метаданные; читаются в момент запроса). */
    private val namesOf: (recordId: String) -> Map<String, String> = { emptyMap() },
    private val clock: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var cachedChunks: List<StoredChunk>? = null
    private var cachedBm25: Bm25Index? = null

    fun isIndexed(recordId: String) = recordId in chunks.recordIds()

    fun hasVectors(recordId: String) = recordId in vectors.recordIds()

    fun indexedRecords(): Set<String> = chunks.recordIds()

    /** Записи с окнами, но без векторов (эмбеддер был недоступен). */
    fun missingVectors(): Set<String> = chunks.recordIds() - vectors.recordIds()

    private fun invalidate() = synchronized(lock) { cachedChunks = null; cachedBm25 = null }

    /** Индексирует (или переиндексирует) запись. [progress] -- (готово окон, всего). Бросает только при ошибке хранилища. */
    fun index(record: SearchRecord, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): IndexReport {
        val t0 = clock()
        vectors.deleteRecord(record.id)
        val draft = Chunker.chunkRecord(record, cfg.chunk, 0)
        val first = if (draft.isEmpty()) 0L else chunks.reserveIds(draft.size)
        val ready = draft.mapIndexed { i, c -> c.copy(id = first + i) }
        chunks.replace(record.id, ready.map { StoredChunk(it, ru.neurosteno.core.summary.SummaryText.terms(it.text, mode = cfg.bm25.stemmer)) })
        invalidate()
        progress(0, ready.size)
        val embedded = embedChunks(record.id, ready, progress)
        return IndexReport(ready.size, embedded, (clock() - t0) / 1_000_000)
    }

    private fun embedChunks(recordId: String, ready: List<Chunk>, progress: (Int, Int) -> Unit): Boolean {
        if (ready.isEmpty()) return true
        val emb = embedder() ?: return false
        return runCatching {
            val out = ArrayList<FloatArray>(ready.size)
            for (batch in ready.chunked(BATCH)) {
                out += emb.embed(batch.map { it.embedText }, false)
                progress(out.size, ready.size)
            }
            check(out.size == ready.size) { "эмбеддер вернул ${out.size} векторов на ${ready.size} окон" }
            vectors.upsert(recordId, ready.map { it.id }, out)
        }.isSuccess
    }

    /** Достраивает векторы записей без них (эмбеддер появился или освободилась память). Возвращает число достроенных записей. */
    fun reindexMissing(progress: (recordId: String, done: Int, total: Int) -> Unit = { _, _, _ -> }): Int {
        var n = 0
        for (id in missingVectors()) {
            val ready = chunks.chunks(id).map { it.chunk }
            if (embedChunks(id, ready) { d, t -> progress(id, d, t) }) n++ else break
        }
        return n
    }

    fun deleteRecord(recordId: String) {
        vectors.deleteRecord(recordId)
        chunks.delete(recordId)
        invalidate()
    }

    /** Убирает из индекса записи, которых больше нет в истории ([alive]). */
    fun retainOnly(alive: Set<String>) {
        for (id in (chunks.recordIds() + vectors.recordIds()) - alive) deleteRecord(id)
    }

    private fun snapshot(): Pair<List<StoredChunk>, Bm25Index> = synchronized(lock) {
        val all = cachedChunks ?: chunks.all().also { cachedChunks = it }
        val bm = cachedBm25 ?: Bm25Index(all.map { it.terms }, cfg.bm25.k1, cfg.bm25.b, cfg.bm25.stemmer).also { cachedBm25 = it }
        all to bm
    }

    /** Окна, найденные по запросу, и разобранное ограничение по говорящему. */
    class Found(val hits: List<Hit>, val speaker: SpeakerQuery, val terms: List<String>, val usedDense: Boolean)

    /**
     * Гибридный поиск. [recordId] -- только эта запись (null -- вся история). Эмбеддер запроса берётся на время вызова; если его нет --
     * только BM25.
     */
    fun search(query: String, recordId: String? = null, k: Int = cfg.topK): Found {
        val (all, bm) = snapshot()
        if (all.isEmpty() || query.isBlank()) return Found(emptyList(), SpeakerQuery(query), emptyList(), false)
        var qvec: FloatArray? = null
        if (vectors.count() > 0) {
            val sq = SpeakerQueryParser.parse(query, if (recordId != null) namesOf(recordId).values else all.map { it.chunk.recordId }.distinct().flatMap { namesOf(it).values })
            val q = sq.clean.ifEmpty { query }
            qvec = runCatching { embedder()?.embed(listOf(q), true)?.firstOrNull() }.getOrNull()
        }
        val searcher = HybridSearcher(all.map { it.chunk }, bm, namesOf, vectors, cfg)
        val r = searcher.search(query, qvec, k, recordId)
        return Found(r.hits, r.speaker, r.terms, qvec != null)
    }

    private companion object {
        const val BATCH = 16
    }
}
