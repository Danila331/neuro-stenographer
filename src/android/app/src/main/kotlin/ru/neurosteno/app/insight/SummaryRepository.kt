package ru.neurosteno.app.insight

import kotlinx.coroutines.flow.StateFlow
import ru.neurosteno.core.Utterance

/**
 * Возможности выжимки на этом устройстве: из конфига уровня (`summary.mode`: `extractive` -- lite, без LLM; `llm` -- medium/hard)
 * и состояния LLM (память, наличие модели). [llmUnavailableReason] == null -- LLM можно запускать.
 */
data class SummaryCaps(
    val llmMode: Boolean,
    val llmUnavailableReason: String? = null,
) {
    val canRunLlm: Boolean get() = llmMode && llmUnavailableReason == null
}

/** Что показывает экран «Выжимка». У [Shown] выжимка может быть быстрой (extractive), частичной или итоговой -- см. [SummaryDoc.mode] и [SummaryDoc.done]. */
sealed interface SummaryState {
    /** Идёт чтение сохранённой выжимки. */
    data object Loading : SummaryState

    /** Выжимки ещё нет: предлагаем составить. */
    data object None : SummaryState

    data class Shown(val doc: SummaryDoc) : SummaryState {
        /** LLM ещё работает: показана быстрая/частичная версия, итоговая заменит её. */
        val working: Boolean get() = !doc.done
    }

    /** Составление не удалось; [doc] -- что удалось показать (быстрая версия), если есть. */
    data class Failed(val message: String, val doc: SummaryDoc? = null) : SummaryState
}

/**
 * Порт выжимки (B50). Настоящая реализация -- на движке `ru.neurosteno.core.summary` (extractive мгновенно, затем LLM в фоне,
 * `summary.json` рядом с записью); пока движок не слит, UI работает на [FakeSummaryRepository].
 */
interface SummaryRepository {
    fun caps(): SummaryCaps

    /** Состояние выжимки записи; при первом обращении читает сохранённую (если есть). */
    fun state(entryId: String): StateFlow<SummaryState>

    /**
     * Составляет выжимку: сразу быстрая (extractive), затем -- если [useLlm] и LLM доступна -- ИИ-версия в фоне, которая её заменит.
     * Повторный вызов («Пересоздать») отменяет текущую работу и начинает заново.
     */
    fun build(entryId: String, utterances: List<Utterance>, useLlm: Boolean)

    /** Останавливает фоновую ИИ-выжимку; быстрая версия остаётся. */
    fun cancel(entryId: String)
}
