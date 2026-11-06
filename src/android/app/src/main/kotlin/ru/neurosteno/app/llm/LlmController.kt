package ru.neurosteno.app.llm

import android.app.ActivityManager
import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.ProcessingState
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.llm.LlamaAssistant

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
        _ui.value = LlmUi(protocol = savedProtocol.orEmpty(), protocolDone = !savedProtocol.isNullOrBlank())
    }

    // ---- доступность ----

    fun availability(): LlmAvailability {
        val (cfg, _) = graph.activeConfig() ?: return LlmAvailability.Unavailable("Не удалось прочитать конфигурацию набора моделей.")
        if (ProcessingState.processing.value is ProcessingUi.Running) {
            return LlmAvailability.Unavailable("Идёт обработка записи. Дождитесь её окончания: модели загружаются по очереди, чтобы хватило памяти.")
        }
        val modelPath = cfg.llm.model ?: return LlmAvailability.Unavailable("В активном наборе моделей нет языковой модели (LLM).")
        val dir = graph.modelStore.activeDir() ?: return LlmAvailability.Unavailable("Набор моделей не установлен: откройте раздел «Модели».")
        if (!File(dir, modelPath).isFile) {
            return LlmAvailability.Unavailable("В активном наборе нет файла языковой модели ($modelPath). Скачайте полный набор в разделе «Модели».")
        }
        val totalMb = totalRamMb()
        if (totalMb < MIN_TOTAL_MB) {
            return LlmAvailability.Unavailable("Недостаточно памяти: для языковой модели нужно не менее 3 ГБ ОЗУ, у устройства около ${String.format(java.util.Locale("ru"), "%.1f", totalMb / 1024.0)} ГБ.")
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
            try {
                val llm = ensureAssistant()
                val cfg = graph.activeConfig()!!.first
                val lines = PromptBuilder.transcriptLines(t)
                if (lines.isEmpty()) throw IllegalStateException("В расшифровке нет реплик")
                val system = prompts.protocolSystem
                val maxNew = PROTOCOL_MAX_TOKENS
                val budget = PromptBuilder.contentBudgetChars(cfg.llm.nCtx, maxNew, system.length)
                var chunks = PromptBuilder.chunk(lines, budget)
                var material = chunks.joinToString("\n")
                if (chunks.size > 1) {
                    // длинная запись: сначала конспект каждого фрагмента, потом протокол по конспектам
                    val notesBudget = PromptBuilder.contentBudgetChars(cfg.llm.nCtx, NOTES_MAX_TOKENS, prompts.notesSystem.length)
                    val frag = PromptBuilder.chunk(lines, notesBudget)
                    val notes = ArrayList<String>()
                    frag.forEachIndexed { i, part ->
                        _ui.update { it.copy(status = "Конспект части ${i + 1} из ${frag.size}…") }
                        val prompt = PromptBuilder.chatml(prompts.notesSystem, "Фрагмент ${i + 1} из ${frag.size}:\n$part")
                        notes += PromptBuilder.clean(generate(llm, prompt, NOTES_MAX_TOKENS) { })
                    }
                    val perNote = budget / frag.size.coerceAtLeast(1)
                    material = notes.mapIndexed { i, n -> "Часть ${i + 1}:\n" + n.take(perNote) }.joinToString("\n\n")
                    chunks = listOf(material)
                }
                _ui.update { it.copy(status = "Составление протокола…") }
                val prompt = PromptBuilder.chatml(system, "Расшифровка:\n$material")
                val sb = StringBuilder()
                generate(llm, prompt, maxNew) { piece ->
                    sb.append(piece)
                    _ui.update { it.copy(protocol = PromptBuilder.clean(sb.toString()), tokens = it.tokens + 1) }
                }
                val text = PromptBuilder.clean(sb.toString())
                _ui.update { it.copy(running = false, protocol = text, protocolDone = true, status = "") }
                if (entryId != null && text.isNotBlank()) graph.history.writeProtocol(entryId, text)
            } catch (e: CancellationException) {
                _ui.update { it.copy(running = false, status = "", protocolDone = it.protocol.isNotBlank(), error = "Остановлено") }
            } catch (e: Throwable) {
                _ui.update { it.copy(running = false, status = "", error = "Не удалось составить протокол: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun ask(question: String, t: EditableTranscript) {
        val q = question.trim()
        if (q.isEmpty() || _ui.value.running) return
        val index = _ui.value.qa.size
        _ui.update { it.copy(running = true, error = null, status = "Загрузка модели…", qa = it.qa + QaItem(q, "", false), startedAtMs = System.currentTimeMillis(), tokens = 0) }
        job = scope.launch {
            try {
                val llm = ensureAssistant()
                val cfg = graph.activeConfig()!!.first
                val system = prompts.qaSystem
                val budget = PromptBuilder.contentBudgetChars(cfg.llm.nCtx, QA_MAX_TOKENS, system.length + q.length)
                val chunks = PromptBuilder.chunk(PromptBuilder.transcriptLines(t), budget)
                val material = PromptBuilder.selectRelevant(chunks, q, budget).joinToString("\n...\n")
                _ui.update { it.copy(status = "Поиск ответа…") }
                val prompt = PromptBuilder.chatml(system, "Расшифровка:\n$material\n\nВопрос: $q")
                val sb = StringBuilder()
                generate(llm, prompt, QA_MAX_TOKENS) { piece ->
                    sb.append(piece)
                    setAnswer(index, PromptBuilder.clean(sb.toString()), done = false)
                    _ui.update { it.copy(tokens = it.tokens + 1) }
                }
                setAnswer(index, PromptBuilder.clean(sb.toString()).ifBlank { "Модель не дала ответа." }, done = true)
                _ui.update { it.copy(running = false, status = "") }
            } catch (e: CancellationException) {
                _ui.update { s ->
                    s.copy(running = false, status = "", qa = s.qa.mapIndexed { i, item -> if (i == index) item.copy(done = true, answer = item.answer.ifBlank { "(остановлено)" }) else item })
                }
            } catch (e: Throwable) {
                _ui.update { s ->
                    s.copy(running = false, status = "", error = "Не удалось ответить: ${e.message ?: e.javaClass.simpleName}",
                        qa = s.qa.mapIndexed { i, item -> if (i == index) item.copy(done = true) else item })
                }
            }
        }
    }

    private fun setAnswer(index: Int, text: String, done: Boolean) {
        _ui.update { s -> s.copy(qa = s.qa.mapIndexed { i, item -> if (i == index) item.copy(answer = text, done = done) else item }) }
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

    private suspend fun ensureAssistant(): LlamaAssistant {
        synchronized(this) { assistant }?.let { return it }
        val (cfg, _) = graph.activeConfig() ?: throw IllegalStateException("нет конфигурации")
        val file = File(graph.modelStore.activeDir() ?: throw IllegalStateException("нет набора моделей"), cfg.llm.model ?: throw IllegalStateException("в наборе нет LLM"))
        val threads = minOf(cfg.llm.nThreads, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val created = runInterruptible(Dispatchers.IO) {
            // GPU не используем (BACKLOG B11): CPU по умолчанию
            LlamaAssistant(file, cfg.llm.nCtx, threads, gpuLayers = 0)
        }
        synchronized(this) { assistant = created }
        return created
    }

    private suspend fun generate(llm: LlamaAssistant, prompt: String, maxTokens: Int, onToken: (String) -> Unit): String =
        runInterruptible(Dispatchers.Default) { llm.generate(prompt, maxTokens, onToken) }

    private companion object {
        const val MIN_TOTAL_MB = 3_000L
        const val PROTOCOL_MAX_TOKENS = 700
        const val NOTES_MAX_TOKENS = 320
        const val QA_MAX_TOKENS = 400
    }
}
