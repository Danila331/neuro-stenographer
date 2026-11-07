package ru.neurosteno.app.insight

import kotlinx.coroutines.flow.StateFlow
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.summary.SummaryDoc

/**
 * Возможности выжимки на этом устройстве: из конфига уровня (`summary.mode`: `extractive` -- lite, без LLM; `llm` -- medium/hard)
 * и состояния LLM (память, наличие модели, идёт ли другая обработка). [llmUnavailableReason] == null -- LLM можно запускать.
 */
data class SummaryCaps(
    val llmMode: Boolean,
    val llmUnavailableReason: String? = null,
) {
    val canRunLlm: Boolean get() = llmMode && llmUnavailableReason == null
}

/** Ход ИИ-выжимки: [stage] -- готовая строка стадии, [done] из [total] фрагментов. */
data class SummaryProgress(val stage: String, val done: Int, val total: Int) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / (total + 1)).coerceIn(0f, 1f) else 0f
}

/** Что показывает экран «Выжимка». */
sealed interface SummaryState {
    /** Идёт чтение сохранённой выжимки. */
    data object Loading : SummaryState

    /** Выжимки ещё нет: предлагаем составить. */
    data object None : SummaryState

    /** Запуск идёт, но готовых данных ещё нет (подготовка, загрузка модели). */
    data class Preparing(val stage: String) : SummaryState

    /**
     * Выжимка есть: быстрая (extractive), частичная или итоговая -- см. [SummaryDoc.mode] и [SummaryDoc.done]. [progress] != null --
     * ИИ-версия готовится, итоговая заменит показанную; [notice] -- пояснение (например, почему ИИ-версии нет).
     */
    data class Shown(val doc: SummaryDoc, val progress: SummaryProgress? = null, val notice: String? = null) : SummaryState {
        val working: Boolean get() = progress != null
    }

    /** Составление не удалось; [doc] -- что удалось показать (быстрая версия), если есть. */
    data class Failed(val message: String, val doc: SummaryDoc? = null) : SummaryState
}

/**
 * Порт выжимки (B50). Настоящая реализация -- [GraphSummaryRepository] поверх `AppGraph.summary` (`SummaryRunner`: extractive сразу,
 * затем LLM по фрагментам; `summary.json` рядом с записью); [FakeSummaryRepository] -- для debug, превью и тестов.
 */
interface SummaryRepository {
    fun caps(): SummaryCaps

    /** Состояние выжимки записи; при первом обращении читает сохранённую (если есть). */
    fun state(entryId: String): StateFlow<SummaryState>

    /**
     * Составляет выжимку: сразу быстрая (extractive), затем -- если [useLlm] и LLM доступна -- ИИ-версия в фоне, которая её заменит.
     * Повторный вызов («Пересоздать») отменяет текущую работу и начинает заново. [utterances] нужны только фейку (настоящая
     * реализация читает расшифровку из истории).
     */
    fun build(entryId: String, utterances: List<Utterance>, useLlm: Boolean)

    /** Останавливает фоновую ИИ-выжимку; быстрая версия остаётся. */
    fun cancel(entryId: String)
}
