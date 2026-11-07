package ru.neurosteno.app.search

import android.content.Context
import android.os.Debug
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.FragmentState
import ru.neurosteno.app.ProcessingState
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.data.StoredTranscript
import ru.neurosteno.core.search.FileChunkStore
import ru.neurosteno.core.search.SearchConfig
import ru.neurosteno.core.search.SearchEngine
import ru.neurosteno.core.search.SearchRecord
import ru.neurosteno.core.summary.SUtt

/** Прогресс индексации записи: [done] из [total] окон. */
data class IndexProgress(val recordId: String, val done: Int, val total: Int)

/**
 * AI-поиск приложения (B51): движок [SearchEngine] поверх файлового хранилища окон и хранилища векторов (ObjectBox либо перебор),
 * фоновая индексация записей и привязка к истории.
 *
 * - Индексация -- в одном фоновом потоке, по очереди, ПОСЛЕ основной обработки; пока идёт обработка, выжимка или пересчёт фрагмента,
 *   ждёт (эмбеддер, LLM и ASR не бывают в памяти одновременно). Эмбеддер загружается на время расчёта и выгружается по простою.
 * - Удаление записи сразу удаляет её окна и векторы; правка расшифровки переиндексирует запись, если изменились тексты/границы
 *   (переименование говорящих -- нет: имена читаются в момент запроса).
 * - При смене модели эмбеддингов/нарезки индекс (его метаданные `search/meta.json`) пересоздаётся.
 */
class SearchService(private val context: Context, private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var bound: Bound? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val hashSer = MapSerializer(String.serializer(), String.serializer())
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private var worker: kotlinx.coroutines.Job? = null
    private val namesCache = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var answering = false

    private val _progress = MutableStateFlow<Map<String, IndexProgress>>(emptyMap())
    /** Записи, которые сейчас индексируются или ждут очереди (done = 0, total = 0 -- в очереди). */
    val progress: StateFlow<Map<String, IndexProgress>> = _progress.asStateFlow()

    val embedder = EmbedderHolder(
        scope,
        canLoad = { !isPipelineBusy() },
        source = {
            val cfg = config() ?: return@EmbedderHolder null
            val dir = graph.modelStore.activeDir() ?: return@EmbedderHolder null
            dir to cfg.embedder
        },
    )

    private class Bound(val sig: String, val cfg: SearchConfig, val engine: SearchEngine, val opened: OpenedStore, val hashes: File)

    fun config(): SearchConfig? = graph.activeConfig()?.first?.search?.takeIf { it.enabled }

    private fun root() = File(graph.filesDir(), "search").also { it.mkdirs() }

    /** Идёт обработка записи или пересчёт фрагмента (ASR/диаризация в памяти): эмбеддер грузить нельзя. */
    private fun isPipelineBusy(): Boolean = ProcessingState.processing.value is ProcessingUi.Running || FragmentState.running

    /** Память занята тяжёлой работой (в т. ч. выжимкой и ответом LLM): фоновую индексацию откладываем. */
    fun isBusy(): Boolean = isPipelineBusy() || graph.summary.isRunning || answering

    /** Почему ответ LLM сейчас недоступен (текст для пользователя) либо null, если можно. */
    fun llmUnavailableReason(): String? {
        val cfg = graph.activeConfig()?.first ?: return "Не удалось прочитать конфигурацию набора моделей."
        if (ProcessingState.processing.value is ProcessingUi.Running) return "Идёт обработка записи. Дождитесь её окончания: модели загружаются по очереди."
        if (graph.summary.isRunning) return "Идёт составление выжимки. Дождитесь её окончания."
        if (answering) return "Ответ уже формируется."
        val modelPath = cfg.llm.model ?: return "В активном наборе моделей нет языковой модели (LLM)."
        val dir = graph.modelStore.activeDir() ?: return "Набор моделей не установлен: откройте раздел «Модели»."
        if (!File(dir, modelPath).isFile) return "В активном наборе нет файла языковой модели ($modelPath)."
        val info = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
        if (info.totalMem / (1024 * 1024) < MIN_LLM_TOTAL_MB) return "Для языковой модели нужно не менее 3 ГБ ОЗУ."
        ru.neurosteno.llm.LlamaAssistant.availability()?.let { return "Языковая модель не поддерживается на этом устройстве: $it." }
        return null
    }

    // ---------------------------------------------------------------- привязка к конфигу -----------------------------------------

    private fun signature(c: SearchConfig) = listOf(c.embedder.model, c.embedder.tokenizer, c.embedder.dim, c.embedder.pooling, c.embedder.queryPrefix, c.embedder.docPrefix, c.chunk, c.bm25.stemmer).joinToString("|")

    fun engine(): SearchEngine? = bind()?.engine

    /** Каким хранилищем фактически пользуемся (`objectbox` | `bruteforce`) и почему не тем, что в конфиге. */
    fun storeInfo(): Pair<String, String?>? = bind()?.let { it.opened.kind to it.opened.note }

    private fun bind(): Bound? = synchronized(lock) {
        val cfg = config() ?: return null
        val sig = signature(cfg)
        bound?.let { if (it.sig == sig) return it }
        bound?.let { runCatching { it.opened.store.close() } }
        bound = null
        val dir = root()
        val meta = File(dir, "meta.json")
        val old = meta.takeIf { it.isFile }?.readText()
        if (old != null && old != sig) {
            Log.i(TAG, "search index: смена модели/нарезки, индекс пересоздаётся")
            dir.listFiles()?.forEach { it.deleteRecursively() }
        }
        meta.writeText(sig)
        val opened = SearchStores.open(context, cfg, dir)
        if (opened.note != null) Log.w(TAG, "search store: ${opened.kind} (${opened.note})")
        val chunks = FileChunkStore(File(dir, "chunks"))
        val engine = SearchEngine(cfg, chunks, opened.store, { embedder.get() }, ::namesOf)
        Bound(sig, cfg, engine, opened, File(dir, "hashes.json")).also { bound = it }
    }

    // ---------------------------------------------------------------- записи ----------------------------------------------------

    fun namesOf(recordId: String): Map<String, String> =
        namesCache.getOrPut(recordId) { graph.history.loadTranscript(recordId)?.speakerNames.orEmpty() }

    fun loadRecord(entryId: String): SearchRecord? {
        val st = graph.history.loadTranscript(entryId) ?: return null
        val entry = graph.history.get(entryId)
        namesCache[entryId] = st.speakerNames
        return SearchRecord(entryId, entry?.title ?: entryId, utts(st), st.speakerNames)
    }

    private fun utts(st: StoredTranscript): List<SUtt> = st.utterances.mapIndexed { i, u ->
        SUtt(i, u.speaker, u.speaker.substringAfter('_', "0").toIntOrNull() ?: 0, u.start, u.end, u.text)
    }

    private fun hashOf(rec: SearchRecord): String {
        val md = MessageDigest.getInstance("SHA-1")
        for (u in rec.utts) md.update("${u.speakerId}|${u.startMs}|${u.endMs}|${u.text}\n".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun readHashes(b: Bound): MutableMap<String, String> =
        runCatching { json.decodeFromString(hashSer, b.hashes.readText()).toMutableMap() }.getOrDefault(mutableMapOf())

    private fun writeHashes(b: Bound, m: Map<String, String>) = runCatching { b.hashes.writeText(json.encodeToString(hashSer, m)) }

    // ---------------------------------------------------------------- события истории -------------------------------------------

    /** Запись добавлена в историю (после основной обработки): поставить в очередь индексации. */
    fun onAdded(entryId: String) = enqueue(entryId)

    /** Расшифровка правилась (в т. ч. пересчёт фрагмента): переиндексировать, если изменились тексты или границы реплик. */
    fun onSaved(entryId: String) {
        namesCache.remove(entryId)
        enqueue(entryId)
    }

    /** Запись удалена: убрать окна и векторы сразу. */
    fun onDeleted(entryId: String) {
        namesCache.remove(entryId)
        queued.remove(entryId)
        _progress.update { it - entryId }
        scope.launch(Dispatchers.IO) {
            val b = bind() ?: return@launch
            runCatching {
                b.engine.deleteRecord(entryId)
                writeHashes(b, readHashes(b).also { it.remove(entryId) })
            }.onFailure { Log.e(TAG, "search: не удалось удалить записи $entryId из индекса", it) }
        }
    }

    fun onCleared() {
        queued.clear()
        _progress.value = emptyMap()
        scope.launch(Dispatchers.IO) {
            val b = bind() ?: return@launch
            runCatching { b.engine.retainOnly(emptySet()); writeHashes(b, emptyMap()) }
        }
    }

    /** При запуске: убрать из индекса удалённые записи, проиндексировать новые и достроить векторы. */
    fun syncAll() {
        scope.launch {
            val b = bind() ?: return@launch
            val ids = graph.history.entries.value.map { it.id }.toSet()
            runCatching { b.engine.retainOnly(ids) }
            val hashes = readHashes(b)
            val indexed = b.engine.indexedRecords()
            for (id in ids) if (id !in indexed || id !in hashes) enqueue(id)
            if (b.engine.missingVectors().isNotEmpty()) enqueue(REINDEX_MISSING)
        }
    }

    private fun enqueue(id: String) {
        if (config() == null) return
        if (!queued.add(id)) return
        if (id != REINDEX_MISSING) _progress.update { it + (id to IndexProgress(id, 0, 0)) }
        ensureWorker()
        queue.trySend(id)
    }

    private fun ensureWorker(): Unit = synchronized(lock) {
        if (worker?.isActive == true) return@synchronized
        worker = scope.launch {
            for (id in queue) {
                try {
                    awaitIdle()
                    if (queued.remove(id)) process(id) // уже не в очереди -- запись удалили, пока ждали
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "search: индексация $id не удалась", e)
                } finally {
                    if (id !in queued) _progress.update { it - id } // повторно поставленная во время работы запись остаётся в очереди
                }
            }
        }
    }

    private suspend fun awaitIdle() {
        var waited = 0
        while (isBusy()) {
            delay(BUSY_POLL_MS)
            waited++
        }
        if (waited > 0) Log.i(TAG, "search: индексация ждала окончания тяжёлой работы ${waited * BUSY_POLL_MS} мс")
    }

    private suspend fun process(id: String): Unit = withContext(Dispatchers.Default) {
        val b = bind() ?: return@withContext
        if (id == REINDEX_MISSING) {
            val n = b.engine.reindexMissing { rid, d, t -> _progress.update { it + (rid to IndexProgress(rid, d, t)) } }
            _progress.update { m -> m.filterKeys { it in queued } }
            Log.i(TAG, "search: достроены векторы у $n записей")
            return@withContext
        }
        val rec = loadRecord(id) ?: return@withContext
        val hashes = readHashes(b)
        val h = hashOf(rec)
        if (hashes[id] == h && b.engine.isIndexed(id) && b.engine.hasVectors(id)) return@withContext
        val pss0 = pssMb()
        val peak = AtomicInteger(pss0)
        val t0 = System.nanoTime()
        val rep = b.engine.index(rec) { done, total ->
            peak.updateAndGet { maxOf(it, pssMb()) }
            _progress.update { it + (id to IndexProgress(id, done, total)) }
        }
        writeHashes(b, readHashes(b).also { it[id] = h })
        Log.i(
            TAG,
            "search_index entry=$id utts=${rec.utts.size} chunks=${rep.chunks} embedded=${rep.embedded} ms=${(System.nanoTime() - t0) / 1_000_000} " +
                "store=${b.opened.kind} pss_mb=${peak.get()} pss_before_mb=$pss0",
        )
    }

    /** Остановить фоновые работы (тесты). */
    fun close() {
        worker?.cancel()
        embedder.release()
        synchronized(lock) { bound?.let { runCatching { it.opened.store.close() } }; bound = null }
    }

    internal fun setAnswering(on: Boolean) { answering = on }

    private fun pssMb(): Int = runCatching { (Debug.getPss() / 1024L).toInt() }.getOrDefault(0)

    companion object {
        private const val TAG = "Neurosteno"
        private const val BUSY_POLL_MS = 2_000L
        private const val MIN_LLM_TOTAL_MB = 3_000L
        private const val REINDEX_MISSING = "\u0000reindex-missing"
    }
}
