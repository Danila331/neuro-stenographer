package ru.neurosteno.app.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.ProcessingState
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.data.ResultsAutosave
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.llm.LlamaAssistant
import ru.neurosteno.llm.toLlamaOptions

/** Можно ли сейчас пользоваться LLM; [Unavailable.reason] показывается пользователю как есть. */
sealed interface LlmAvailability {
    data object Available : LlmAvailability
    data class Unavailable(val reason: String) : LlmAvailability
}

data class QaItem(val question: String, val answer: String, val done: Boolean)

data class LlmUi(
    val running: Boolean = false,
    /** «Загрузка модели…», «Часть 2 из 4», «Генерация ответа» -- что делается сейчас. */
    val status: String = "",
    /** Текст протокола (потоковый вывод по мере генерации). */
    val protocol: String = "",
    val protocolDone: Boolean = false,
    val qa: List<QaItem> = emptyList(),
    val error: String? = null,
    val startedAtMs: Long = 0,
    val tokens: Int = 0,
    /** `llm.quality = draft` (упрощённая модель, lite): интерфейс помечает протокол как черновой. */
    val draft: Boolean = false,
)

/**
 * Работа с локальной LLM после основной обработки: «Протокол заседания» и вопросы по расшифровке. Модель загружается при
 * первом запросе и выгружается в [release] (ASR/диаризация к этому времени уже выгружены оркестратором). Один запрос
 * за раз; остановка -- прерыванием потока генерации (llama.cpp проверяет флаг на каждом токене).
 */
class LlmController(private val context: Context, private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var assistant: LlamaAssistant? = null
    private val prompts by lazy { Prompts(context.assets) }

    private val _ui = MutableStateFlow(LlmUi())
    val ui: StateFlow<LlmUi> = _ui.asStateFlow()

    /** Для какой записи накоплено состояние (при смене записи сбрасывается). */
    @Volatile private var boundEntry: String? = null

    fun bind(entryId: String, savedProtocol: String?) {
        if (boundEntry == entryId) return
        stop()
        boundEntry = entryId
        _ui.value = LlmUi(protocol = savedProtocol.orEmpty(), protocolDone = !savedProtocol.isNullOrBlank(), draft = graph.activeConfig()?.first?.llm?.isDraft == true)
    }

    // ---- доступность ----

    fun availability(): LlmAvailability {
        val (cfg, _) = graph.activeConfig() ?: return LlmAvailability.Unavailable("Не удалось прочитать конфигурацию набора моделей.")
        if (ProcessingState.processing.value is ProcessingUi.Running) {
            return LlmAvailability.Unavailable("Идёт обработка записи. Дождитесь её окончания: модели загружаются по очереди, чтобы хватило памяти.")
        }
        if (graph.summary.isRunning) {
            return LlmAvailability.Unavailable("Идёт составление выжимки. Дождитесь её окончания: языковая модель загружается один раз.")
        }
        val modelPath = cfg.llm.model ?: return LlmAvailability.Unavailable("В активном наборе моделей нет языковой модели (LLM).")
        val dir = graph.modelStore.activeDir() ?: return LlmAvailability.Unavailable("Набор моделей не установлен: откройте раздел «Модели».")
        if (!File(dir, modelPath).isFile) {
            return LlmAvailability.Unavailable("В активном наборе нет файла языковой модели ($modelPath). Скачайте полный набор в разделе «Модели».")
        }
        val totalMb = totalRamMb()
        if (totalMb < MIN_TOTAL_MB) {
            return LlmAvailability.Unavailable("Недостаточно памяти: для языковой модели нужно не менее 3 ГБ ОЗУ, у устройства около ${String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", totalMb / 1024.0)} ГБ.")
        }
        LlamaAssistant.availability()?.let { return LlmAvailability.Unavailable("Языковая модель не поддерживается на этом устройстве: $it.") }
        return LlmAvailability.Available
    }

    private fun totalRamMb(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    // ---- запросы ----

    fun generateProtocol(t: EditableTranscript) {
        if (_ui.value.running) return
        _ui.update { it.copy(running = true, error = null, protocol = "", protocolDone = false, status = "Загрузка модели…", startedAtMs = System.currentTimeMillis(), tokens = 0) }
        val entryId = boundEntry
        job = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val peakPss = AtomicInteger(currentPssMb())
            val sampler = samplePeakPss(peakPss)
            var model = "unknown"
            var promptTokens = 0
            var generatedTokens = 0
            var status = "error"
            var options = ""
            try {
                val cfg = graph.activeConfig()?.first ?: throw IllegalStateException("нет конфигурации")
                val modelPath = cfg.llm.model ?: throw IllegalStateException("в наборе нет LLM")
                model = modelName(modelPath)
                val llm = ensureAssistant(cfg)
                options = llm.appliedOptions
                val lines = PromptBuilder.transcriptLines(t)
                if (lines.isEmpty()) throw IllegalStateException("В расшифровке нет реплик")
                _ui.update { it.copy(draft = cfg.llm.isDraft) }
                val flow = ProtocolFlow(cfg.llm.nCtx, cfg.llm.chunkMergeProtocol, cfg.llm.filterQuotes, prompts.protocolSystem, prompts.notesSystem)
                // весь поток -- в одном runInterruptible: отмена корутины прерывает поток, и llama.cpp останавливается на ближайшем токене
                val text = runInterruptible(Dispatchers.Default) {
                    flow.run(
                        lines = lines,
                        utteranceTexts = t.utterances.map { it.text },
                        generate = ProtocolFlow.Generate { prompt, maxTokens, onPiece -> llm.generate(prompt, maxTokens, onPiece) },
                        status = ProtocolFlow.Status { st -> _ui.update { it.copy(status = st) } },
                        onPrompt = { promptTokens += estimatePromptTokens(it) },
                        onPiece = { generatedTokens++; _ui.update { it.copy(tokens = it.tokens + 1) } },
                        onText = { shown -> _ui.update { it.copy(protocol = shown) } },
                    )
                }
                _ui.update { it.copy(running = false, protocol = text, protocolDone = true, status = "") }
                if (entryId != null && text.isNotBlank()) graph.history.writeProtocol(entryId, text)
                status = "ok"
            } catch (e: CancellationException) {
                status = "cancelled"
                _ui.update { it.copy(running = false, status = "", protocolDone = it.protocol.isNotBlank(), error = "Остановлено") }
            } catch (e: Throwable) {
                status = "error"
                _ui.update { it.copy(running = false, status = "", error = "Не удалось составить протокол: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                finishDiagnostics(entryId, "protocol", model, promptTokens, generatedTokens, startedAt, peakPss, status, sampler, options)
            }
        }
    }

    fun ask(question: String, t: EditableTranscript) {
        val q = question.trim()
        if (q.isEmpty() || _ui.value.running) return
        val index = _ui.value.qa.size
        _ui.update { it.copy(running = true, error = null, status = "Загрузка модели…", qa = it.qa + QaItem(q, "", false), startedAtMs = System.currentTimeMillis(), tokens = 0) }
        val entryId = boundEntry
        job = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val peakPss = AtomicInteger(currentPssMb())
            val sampler = samplePeakPss(peakPss)
            var model = "unknown"
            var promptTokens = 0
            var generatedTokens = 0
            var status = "error"
            var options = ""
            try {
                val cfg = graph.activeConfig()?.first ?: throw IllegalStateException("нет конфигурации")
                val modelPath = cfg.llm.model ?: throw IllegalStateException("в наборе нет LLM")
                model = modelName(modelPath)
                val llm = ensureAssistant(cfg)
                options = llm.appliedOptions
                val system = prompts.qaSystem
                val budget = PromptBuilder.contentBudgetChars(cfg.llm.nCtx, QA_MAX_TOKENS, system.length + q.length)
                val material = PromptBuilder.qaMaterial(PromptBuilder.transcriptLines(t), q, budget, cfg.llm.qaChunkBudgetRatio).joinToString("\n...\n")
                _ui.update { it.copy(status = "Поиск ответа…") }
                val prompt = PromptBuilder.chatml(system, "Расшифровка:\n$material\n\nВопрос: $q")
                promptTokens += estimatePromptTokens(prompt)
                val sb = StringBuilder()
                var runaway = false
                try {
                    generate(llm, prompt, QA_MAX_TOKENS) { piece ->
                        generatedTokens++
                        sb.append(piece)
                        if (PromptBuilder.runawayIndex(sb.toString()) >= 0) {
                            // модель начала выдумывать следующий вопрос -- прерываем генерацию (llama.cpp проверяет флаг потока)
                            runaway = true
                            Thread.currentThread().interrupt()
                        } else {
                            setAnswer(index, PromptBuilder.clean(sb.toString()), done = false)
                        }
                        _ui.update { it.copy(tokens = it.tokens + 1) }
                    }
                } catch (e: CancellationException) {
                    if (!runaway) throw e
                }
                val full = sb.toString()
                val cut = if (runaway) full.substring(0, PromptBuilder.runawayIndex(full).coerceAtLeast(0)) else full
                setAnswer(index, PromptBuilder.clean(cut).ifBlank { "Модель не дала ответа." }, done = true)
                _ui.update { it.copy(running = false, status = "") }
                status = "ok"
            } catch (e: CancellationException) {
                status = "cancelled"
                _ui.update { s ->
                    s.copy(running = false, status = "", qa = s.qa.mapIndexed { i, item -> if (i == index) item.copy(done = true, answer = item.answer.ifBlank { "(остановлено)" }) else item })
                }
            } catch (e: Throwable) {
                status = "error"
                _ui.update { s ->
                    s.copy(running = false, status = "", error = "Не удалось ответить: ${e.message ?: e.javaClass.simpleName}",
                        qa = s.qa.mapIndexed { i, item -> if (i == index) item.copy(done = true) else item })
                }
            } finally {
                finishDiagnostics(entryId, "qa", model, promptTokens, generatedTokens, startedAt, peakPss, status, sampler, options)
            }
        }
    }

    private fun setAnswer(index: Int, text: String, done: Boolean) {
        val shown = ru.neurosteno.app.insight.AnswerPolish.dedupe(text) // повторы предложений у малых моделей
        _ui.update { s -> s.copy(qa = s.qa.mapIndexed { i, item -> if (i == index) item.copy(answer = shown, done = done) else item }) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Выгружает модель (освобождает ~1 ГБ) -- при уходе с экрана. */
    fun release() {
        stop()
        // close() берёт тот же замок, что и generate(): ждём окончания отменённой генерации не в потоке интерфейса
        Thread({
            synchronized(this@LlmController) {
                runCatching { assistant?.close() }
                assistant = null
            }
        }, "llm-release").start()
    }

    fun clearError() { _ui.update { it.copy(error = null) } }

    // ---- внутреннее ----

    private suspend fun ensureAssistant(cfg: ru.neurosteno.core.TierConfig): LlamaAssistant {
        synchronized(this) { assistant }?.let { return it }
        val file = File(graph.modelStore.activeDir() ?: throw IllegalStateException("нет набора моделей"), cfg.llm.model ?: throw IllegalStateException("в наборе нет LLM"))
        val threads = minOf(cfg.llm.nThreads, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val created = runInterruptible(Dispatchers.IO) {
            // GPU не используем (BACKLOG B11): CPU по умолчанию
            LlamaAssistant(file, cfg.llm.nCtx, threads, gpuLayers = 0, options = cfg.llm.toLlamaOptions())
        }
        synchronized(this) { assistant = created }
        return created
    }

    private fun samplePeakPss(peak: AtomicInteger): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            peak.updateAndGet { previous -> maxOf(previous, currentPssMb()) }
            delay(PSS_SAMPLE_INTERVAL_MS)
        }
    }

    private fun finishDiagnostics(
        entryId: String?, kind: String, model: String, promptTokens: Int, generatedTokens: Int,
        startedAt: Long, peakPss: AtomicInteger, status: String, sampler: Job, options: String,
    ) {
        sampler.cancel()
        peakPss.updateAndGet { previous -> maxOf(previous, currentPssMb()) }
        val durationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        val run = LlmRunMetrics(
            kind = kind,
            model = model,
            promptTokens = promptTokens,
            generatedTokens = generatedTokens,
            tokPerSecond = if (durationMs > 0) generatedTokens * 1000.0 / durationMs else 0.0,
            durationMs = durationMs,
            peakPssMb = peakPss.get(),
            status = status,
            options = options,
        )
        android.util.Log.i("Neurosteno", LlmDiagnostics.doneLine(run))
        val sourceName = entryId?.let { graph.history.get(it)?.sourceName }
        if (sourceName == null) {
            android.util.Log.w("Neurosteno", "llm marker not written: source for entry is unavailable")
            return
        }
        val resultsDir = ResultsAutosave.resultsDir(graph.externalFilesDir(), graph.filesDir(), android.os.Process.myUid())
        LlmDiagnostics.writeMarker(resultsDir, sourceName, run).onFailure {
            android.util.Log.e("Neurosteno", "llm marker not written for $sourceName", it)
        }
    }

    private fun currentPssMb(): Int = runCatching { (Debug.getPss() / 1024L).toInt() }.getOrDefault(0)

    private fun modelName(path: String): String = File(path).name.replace(Regex("\\s+"), "_").ifBlank { "unknown" }

    /** llama.cpp's app-facing API does not expose prompt tokenization; this is a UTF-8-size estimate. */
    private fun estimatePromptTokens(prompt: String): Int = (prompt.toByteArray(Charsets.UTF_8).size + 3) / 4

    private suspend fun generate(llm: LlamaAssistant, prompt: String, maxTokens: Int, onToken: (String) -> Unit): String =
        runInterruptible(Dispatchers.Default) { llm.generate(prompt, maxTokens, onToken) }

    private companion object {
        const val MIN_TOTAL_MB = 3_000L
        const val QA_MAX_TOKENS = 400
        const val PSS_SAMPLE_INTERVAL_MS = 100L
    }
}
