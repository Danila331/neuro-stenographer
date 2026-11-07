package ru.neurosteno.app.insight

import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.llm.PromptBuilder
import ru.neurosteno.app.search.SearchService
import ru.neurosteno.core.search.AnswerBuilder
import ru.neurosteno.core.search.Hit
import ru.neurosteno.core.search.SearchRecord
import ru.neurosteno.llm.LlamaAssistant
import ru.neurosteno.llm.toLlamaOptions

/**
 * Настоящий [SearchRepository] (B51): гибридный поиск BM25 + эмбеддинги + RRF на `ru.neurosteno.core.search` и ответ LLM по топ-3
 * окнам со ссылками `[мм:сс, Говорящий N]` (недействительные ссылки удаляются -- [AnswerBuilder.verify]).
 */
class RealSearchRepository(private val graph: AppGraph, private val service: SearchService = graph.search) : SearchRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val records = object : LinkedHashMap<String, SearchRecord>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchRecord>?) = size > RECORD_CACHE
    }
    /** Окна последнего поиска: по ним строится контекст ответа (ключ -- запись + границы окна). */
    private val lastHits = HashMap<String, Hit>()

    private val indexStates: StateFlow<IndexState> by lazy {
        service.progress.map { p ->
            when {
                service.config() == null -> IndexState.Unavailable("Поиск выключен в этом варианте приложения")
                p.isEmpty() -> IndexState.Ready
                else -> {
                    val total = p.values.sumOf { it.total }.coerceAtLeast(p.size)
                    IndexState.Building(p.values.sumOf { it.done }, total)
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, IndexState.Ready)
    }

    override fun indexState(scope: SearchScope, recordId: String?): StateFlow<IndexState> =
        if (scope == SearchScope.RECORD && recordId != null) {
            service.progress.map { p ->
                p[recordId]?.let { IndexState.Building(it.done, it.total.coerceAtLeast(1)) }
                    ?: if (service.config() == null) IndexState.Unavailable("Поиск выключен в этом варианте приложения") else IndexState.Ready
            }.stateIn(this.scope, SharingStarted.Eagerly, IndexState.Ready)
        } else indexStates

    override fun answerCaps(): AnswerCaps {
        val cfg = service.config() ?: return AnswerCaps(enabled = false)
        if (!cfg.answer.enabled) return AnswerCaps(enabled = false)
        return AnswerCaps(enabled = true, reason = service.llmUnavailableReason())
    }

    private fun record(id: String): SearchRecord? = synchronized(records) { records[id] } ?: service.loadRecord(id)?.also { synchronized(records) { records[id] = it } }

    override suspend fun search(query: String, scope: SearchScope, recordId: String?): List<SearchHit> = withContext(Dispatchers.Default) {
        val engine = service.engine() ?: return@withContext emptyList()
        val rid = if (scope == SearchScope.RECORD) recordId else null
        val t0 = System.nanoTime()
        val found = engine.search(query, rid, k = FETCH)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // окна шага 1 перекрываются: показываем лучшие без пересечения (общих реплик одной записи)
        val picked = ArrayList<Hit>()
        for (h in found.hits) {
            if (picked.none { it.chunk.recordId == h.chunk.recordId && it.chunk.utts.intersect(h.chunk.utts.toSet()).size >= 2 }) picked += h
            if (picked.size == TOP) break
        }
        synchronized(lastHits) {
            lastHits.clear()
            for (h in picked) lastHits[key(h.chunk.recordId, h.chunk.startMs, h.chunk.endMs)] = h
        }
        Log.i(TAG, "search_query scope=${scope.name} hits=${picked.size} dense=${found.usedDense} ms=$ms store=${service.storeInfo()?.first}")
        picked.mapNotNull { h ->
            val rec = record(h.chunk.recordId) ?: return@mapNotNull null
            SearchHit(
                recordId = rec.id, recordTitle = rec.title, startMs = h.chunk.startMs, endMs = h.chunk.endMs,
                lines = h.chunk.utts.mapNotNull { i -> rec.utts.getOrNull(i) }.map { u -> HitLine(u.speakerId, nameOf(rec, u.speakerId), u.startMs, u.text) },
                score = h.score.toFloat(), terms = found.terms,
            )
        }
    }

    private fun nameOf(rec: SearchRecord, speakerId: String): String =
        rec.names[speakerId]?.takeIf { it.isNotBlank() } ?: speakerId.substringAfter('_').toIntOrNull()?.let { "Говорящий $it" } ?: speakerId

    private fun key(rid: String, s: Long, e: Long) = "$rid:$s:$e"

    override fun answer(query: String, hits: List<SearchHit>): Flow<AnswerEvent> = channelFlow {
        val cfg = service.config()
        val ans = cfg?.answer
        if (ans == null || !ans.enabled) { send(AnswerEvent.Failed("Ответ ИИ выключен в этом варианте.")); return@channelFlow }
        service.llmUnavailableReason()?.let { send(AnswerEvent.Failed(it)); return@channelFlow }
        val top = synchronized(lastHits) { hits.take(ans.topChunks).mapNotNull { lastHits[key(it.recordId, it.startMs, it.endMs)] } }
        if (top.isEmpty()) { send(AnswerEvent.Failed("Нет найденных фрагментов для ответа.")); return@channelFlow }
        val recs = top.map { it.chunk.recordId }.distinct().mapNotNull { id -> record(id) }.associateBy { it.id }
        val ctx = AnswerBuilder.context(top, recs, ans.maxContextChars)
        val system = runCatching { graph.appContext.assets.open("prompts/search_answer_system.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SYSTEM
        val prompt = PromptBuilder.chatml(system, AnswerBuilder.userMessage(ctx, query))
        if (ru.neurosteno.app.BuildConfig.DEBUG) Log.d(TAG, "search_answer prompt:\n$prompt") // только debug: в release текст записи в журнал не попадает
        val c = graph.activeConfig()?.first ?: run { send(AnswerEvent.Failed("Нет конфигурации набора моделей.")); return@channelFlow }
        val modelFile = File(graph.modelStore.activeDir() ?: run { send(AnswerEvent.Failed("Набор моделей не установлен.")); return@channelFlow }, c.llm.model ?: "")
        service.setAnswering(true)
        service.embedder.release() // эмбеддер и LLM не в памяти одновременно
        val t0 = System.nanoTime()
        var llm: LlamaAssistant? = null
        try {
            llm = runInterruptible(Dispatchers.IO) {
                LlamaAssistant(modelFile, c.llm.nCtx, minOf(c.llm.nThreads, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1), gpuLayers = 0, options = c.llm.toLlamaOptions())
            }
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            val sb = StringBuilder()
            var pieces = 0
            val t1 = System.nanoTime()
            runInterruptible(Dispatchers.Default) {
                llm.generate(prompt, ans.maxNewTokens) { piece ->
                    pieces++
                    sb.append(piece)
                    trySend(AnswerEvent.Partial(PromptBuilder.clean(sb.toString())))
                }
            }
            val verified = AnswerBuilder.verify(PromptBuilder.clean(sb.toString()), ctx.cites)
            val text = verified.text.ifBlank { AnswerBuilder.NO_ANSWER }
            Log.i(TAG, "search_answer ctx_chars=${ctx.text.length} cites_ok=${verified.validCites.size} cites_bad=${verified.badCites} refused=${verified.refused} pieces=$pieces load_ms=$loadMs gen_ms=${(System.nanoTime() - t1) / 1_000_000}")
            send(AnswerEvent.Done(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            send(AnswerEvent.Failed("Не хватило памяти для языковой модели."))
        } catch (e: Throwable) {
            Log.e(TAG, "search answer failed", e)
            send(AnswerEvent.Failed("Не удалось получить ответ: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { llm?.close() }
            service.setAnswering(false)
        }
    }

    private companion object {
        const val TAG = "Neurosteno"
        const val FETCH = 12
        const val TOP = 5
        const val RECORD_CACHE = 12
        const val DEFAULT_SYSTEM = "Ты помощник по записям разговоров. Ответь на вопрос только по найденным фрагментам. Фрагмент — это строка «[мм:сс, Говорящий N] текст». " +
            "После каждого утверждения поставь ссылку [мм:сс, Говорящий N], скопировав её без изменений. Если во фрагментах нет ответа, напиши ровно: «В найденных фрагментах этого нет.»"
    }
}
