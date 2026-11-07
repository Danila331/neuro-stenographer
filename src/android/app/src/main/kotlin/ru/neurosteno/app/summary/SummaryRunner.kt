package ru.neurosteno.app.summary

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.ProcessingState
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.data.ResultsAutosave
import ru.neurosteno.app.llm.LlmDiagnostics
import ru.neurosteno.app.llm.LlmRunMetrics
import ru.neurosteno.core.search.TextEmbedder
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryEngine
import ru.neurosteno.core.summary.SummaryPrompts
import ru.neurosteno.llm.LlamaAssistant
import ru.neurosteno.llm.toLlamaOptions

/** Можно ли сейчас составлять LLM-выжимку; [Unavailable.reason] показывается пользователю как есть. */
sealed interface SummaryAvailability {
    data object Available : SummaryAvailability
    data class Unavailable(val reason: String) : SummaryAvailability
}

/**
 * Состояние выжимки для экрана. [doc] -- последняя известная версия: extractive (сразу), частичная LLM (после каждого фрагмента,
 * [SummaryDoc.done] = false) или итоговая. [stage] -- человекочитаемая стадия («Загрузка модели…», «Фрагмент 3 из 9»).
 */
data class SummaryUi(
    val entryId: String? = null,
    val status: Status = Status.Idle,
    val stage: String = "",
    val fragmentsDone: Int = 0,
    val fragments: Int = 0,
    val doc: SummaryDoc? = null,
    val error: String? = null,
    val startedAtMs: Long = 0,
) {
    enum class Status { Idle, Running, Done, Cancelled, Failed }
}

/**
 * Выжимка записи (B50). Запускается из [ru.neurosteno.app.ProcessingService] (после основной обработки при `summary.auto`
 * либо по кнопке -- `ProcessingService.summaryIntent`); состояние -- [ui]. Порядок: extractive-версия сразу (если
 * `summary.show_extractive_first`), затем LLM по фрагментам с записью `summary.json` после каждого фрагмента.
 *
 * Результат: `history/<id>/summary.json` и `<results>/<имя>.summary.json` (рядом с `<имя>.json`); диагностика -- строка logcat
 * `llm_done kind=summary ...` и маркер `<имя>.summary.llm.json`. Один запуск за раз; отмена -- отменой корутины сервиса.
 *
 * [embedder] -- эмбеддер реплик для долин (после появления модуля поиска); `null` -- запасные лексические векторы.
 */
class SummaryRunner(private val context: Context, private val graph: AppGraph, private val embedder: () -> TextEmbedder? = { null }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)
    private val prompts by lazy {
        SummaryPrompts(asset("summary_system.txt") ?: DEFAULT_SYSTEM, asset("summary_overview_system.txt") ?: DEFAULT_OVERVIEW)
    }

    private val _ui = MutableStateFlow(SummaryUi())
    val ui: StateFlow<SummaryUi> = _ui.asStateFlow()

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Сохранённая выжимка записи (или null). Дёшево: читает один файл. */
    fun load(entryId: String): SummaryDoc? =
        graph.history.readSummary(entryId)?.let { runCatching { json.decodeFromString(SummaryDoc.serializer(), it) }.getOrNull() }

    val isRunning: Boolean get() = busy.get()

    fun availability(): SummaryAvailability {
        val (cfg, _) = graph.activeConfig() ?: return SummaryAvailability.Unavailable("Не удалось прочитать конфигурацию набора моделей.")
        if (!cfg.summary.usesLlm) return SummaryAvailability.Available // extractive
        if (ProcessingState.processing.value is ProcessingUi.Running) {
            return SummaryAvailability.Unavailable("Идёт обработка записи. Дождитесь её окончания: модели загружаются по очереди.")
        }
        val modelPath = cfg.llm.model ?: return SummaryAvailability.Unavailable("В активном наборе моделей нет языковой модели (LLM).")
        val dir = graph.modelStore.activeDir() ?: return SummaryAvailability.Unavailable("Набор моделей не установлен: откройте раздел «Модели».")
        if (!File(dir, modelPath).isFile) return SummaryAvailability.Unavailable("В активном наборе нет файла языковой модели ($modelPath).")
        val total = totalRamMb()
        if (total < MIN_TOTAL_MB) return SummaryAvailability.Unavailable("Для LLM-выжимки нужно не менее 3 ГБ ОЗУ.")
        LlamaAssistant.availability()?.let { return SummaryAvailability.Unavailable("Языковая модель не поддерживается на этом устройстве: $it.") }
        return SummaryAvailability.Available
    }

    /**
     * Составляет выжимку записи [entryId] и сохраняет её. Возвращает итоговый статус; ошибки не бросает (кроме отмены -- её
     * перебрасывает вызвавшей корутине после обновления состояния). [onProgress] -- доля 0..1 для уведомления.
     */
    suspend fun run(entryId: String, onProgress: (text: String, fraction: Float) -> Unit = { _, _ -> }): SummaryUi.Status {
        if (!busy.compareAndSet(false, true)) return SummaryUi.Status.Running
        val startedAt = SystemClock.elapsedRealtime()
        val peakPss = AtomicInteger(currentPssMb())
        val sampler = scope.launch(Dispatchers.IO) { while (isActive) { peakPss.updateAndGet { maxOf(it, currentPssMb()) }; delay(PSS_SAMPLE_MS) } }
        var llmModel = ""
        var promptChars = 0
        var pieces = 0
        var options = ""
        var status = "error"
        try {
            val (cfg, _) = graph.activeConfig() ?: throw IllegalStateException("нет конфигурации")
            val sc = cfg.summary
            val entry = graph.history.get(entryId) ?: throw IllegalStateException("запись не найдена")
            val transcript = graph.history.loadTranscript(entryId)?.toTranscript() ?: throw IllegalStateException("не удалось прочитать расшифровку")
            val resultsDir = ResultsAutosave.resultsDir(graph.externalFilesDir(), graph.filesDir(), android.os.Process.myUid())
            val engine = SummaryEngine(sc, prompts)
            _ui.value = SummaryUi(entryId, SummaryUi.Status.Running, "Подготовка…", startedAtMs = System.currentTimeMillis())

            fun publish(doc: SummaryDoc, stage: String) {
                save(entryId, entry.sourceName, resultsDir, doc)
                _ui.value = _ui.value.copy(doc = doc, stage = stage, fragments = doc.fragments, fragmentsDone = doc.fragmentsDone)
            }

            val emb = embedder()
            if (sc.showExtractiveFirst || !sc.usesLlm) {
                val first = withContext(Dispatchers.Default) { engine.extractive(transcript.utterances, emb) }
                publish(first, if (sc.usesLlm) "Черновая выжимка готова, уточняю…" else "")
            }
            if (!sc.usesLlm) {
                _ui.value = _ui.value.copy(status = SummaryUi.Status.Done, stage = "")
                status = "ok"
                return SummaryUi.Status.Done
            }
            val avail = availability()
            if (avail is SummaryAvailability.Unavailable) {
                // extractive-версия уже сохранена -- это штатный запасной результат
                _ui.value = _ui.value.copy(status = SummaryUi.Status.Done, stage = "", error = avail.reason)
                status = "no_llm"
                return SummaryUi.Status.Done
            }

            onProgress("Загрузка модели…", 0f)
            _ui.value = _ui.value.copy(stage = "Загрузка модели…")
            val modelPath = cfg.llm.model!!
            llmModel = File(modelPath).name
            val file = File(graph.modelStore.activeDir()!!, modelPath)
            val threads = minOf(cfg.llm.nThreads, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
            val llm = runInterruptible(Dispatchers.IO) {
                LlamaAssistant(file, maxOf(sc.nCtx, cfg.llm.nCtx), threads, gpuLayers = 0, options = cfg.llm.toLlamaOptions())
            }
            try {
                options = llm.appliedOptions
                val result = runInterruptible(Dispatchers.Default) {
                    engine.summarize(
                        transcript.utterances, emb, llm, llmModel,
                        progress = { stage, done, total ->
                            val text = when (stage) {
                                "overview" -> "Общая выжимка…"
                                "fragment" -> "Фрагмент ${done + 1} из $total"
                                else -> "Разбор по темам…"
                            }
                            val frac = if (total > 0) done.toFloat() / (total + 1) else 0f
                            _ui.value = _ui.value.copy(stage = text, fragments = total, fragmentsDone = done)
                            onProgress(text, frac)
                        },
                        onPartial = { partial -> publish(partial, "Фрагмент ${partial.fragmentsDone} из ${partial.fragments}") },
                    )
                }
                promptChars = result.calls.sumOf { it.promptChars }
                pieces = result.calls.sumOf { it.generatedPieces }
                publish(result.doc, "")
                android.util.Log.i(
                    "Neurosteno",
                    "summary_done entry=$entryId mode=llm fragments=${result.doc.fragments} topics=${result.doc.topics.size} raw_topics=${result.rawTopics} ${result.stats}",
                )
                _ui.value = _ui.value.copy(status = SummaryUi.Status.Done, stage = "")
                status = "ok"
            } finally {
                runCatching { llm.close() }
            }
            return SummaryUi.Status.Done
        } catch (e: CancellationException) {
            status = "cancelled"
            _ui.value = _ui.value.copy(status = SummaryUi.Status.Cancelled, stage = "", error = "Остановлено")
            throw e
        } catch (e: OutOfMemoryError) {
            _ui.value = _ui.value.copy(status = SummaryUi.Status.Failed, stage = "", error = "Не хватило памяти для выжимки.")
            return SummaryUi.Status.Failed
        } catch (e: Throwable) {
            android.util.Log.e("Neurosteno", "summary failed", e)
            _ui.value = _ui.value.copy(status = SummaryUi.Status.Failed, stage = "", error = "Не удалось составить выжимку: ${e.message ?: e.javaClass.simpleName}")
            return SummaryUi.Status.Failed
        } finally {
            sampler.cancel()
            busy.set(false)
            if (llmModel.isNotEmpty()) diagnostics(entryId, llmModel, promptChars, pieces, startedAt, peakPss.get(), status, options)
        }
    }

    private fun save(entryId: String, sourceName: String, resultsDir: File, doc: SummaryDoc) {
        val text = json.encodeToString(SummaryDoc.serializer(), doc)
        runCatching { graph.history.writeSummary(entryId, text) }.onFailure { android.util.Log.e("Neurosteno", "summary not saved to history", it) }
        runCatching {
            resultsDir.mkdirs()
            val target = File(resultsDir, "${ResultsAutosave.baseName(sourceName)}.summary.json")
            val tmp = File(resultsDir, target.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) { target.writeText(text, Charsets.UTF_8); tmp.delete() }
        }.onFailure { android.util.Log.e("Neurosteno", "summary not saved to results", it) }
    }

    private fun diagnostics(entryId: String, model: String, promptChars: Int, pieces: Int, startedAt: Long, peakPss: Int, status: String, options: String) {
        val ms = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        val run = LlmRunMetrics(
            kind = "summary", model = model, promptTokens = promptChars / 2, generatedTokens = pieces,
            tokPerSecond = if (ms > 0) pieces * 1000.0 / ms else 0.0, durationMs = ms, peakPssMb = maxOf(peakPss, currentPssMb()), status = status, options = options,
        )
        android.util.Log.i("Neurosteno", LlmDiagnostics.doneLine(run))
        val source = graph.history.get(entryId)?.sourceName ?: return
        val dir = ResultsAutosave.resultsDir(graph.externalFilesDir(), graph.filesDir(), android.os.Process.myUid())
        LlmDiagnostics.writeMarker(dir, source, run, fileSuffix = "summary.llm").onFailure { android.util.Log.e("Neurosteno", "summary marker not written", it) }
    }

    private fun asset(name: String): String? =
        runCatching { context.assets.open("prompts/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun totalRamMb(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    private fun currentPssMb(): Int = runCatching { (Debug.getPss() / 1024L).toInt() }.getOrDefault(0)

    private companion object {
        const val MIN_TOTAL_MB = 3_000L
        const val PSS_SAMPLE_MS = 200L
        // Запасные тексты на случай отсутствия файлов в assets (рабочие -- prompts/summary_*.txt из bench_rag).
        const val DEFAULT_SYSTEM = "Ты секретарь. По фрагменту расшифровки (строки «[номер] мм:сс Говорящий K: реплика») составь выжимку в виде JSON на русском языке."
        const val DEFAULT_OVERVIEW = "Ты секретарь. Напиши связный текст из двух предложений: о чём говорили и что решили. Только факты из списка."
    }
}
