package ru.neurosteno.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.core.Stage

/** Состояние обработки: единственный источник правды для UI, сервис пишет, Activity читает. */
sealed interface ProcessingUi {
    data object Idle : ProcessingUi

    /**
     * [fractions] -- прогресс каждой стадии (ASR и диаризация идут параллельно, поэтому не одна «текущая»);
     * [stage]/[stageFraction] -- последняя сообщившая стадия; [overall] -- общий прогресс 0..1.
     */
    data class Running(
        val sourceName: String,
        val stage: Stage = Stage.DECODE,
        val stageFraction: Float = 0f,
        val overall: Float = 0f,
        val fractions: Map<Stage, Float> = emptyMap(),
        val startedAtMs: Long = System.currentTimeMillis(),
        val audioDurationMs: Long? = null,
        /** true -- идёт отмена (кнопка «Отменить» уже нажата). */
        val cancelling: Boolean = false,
    ) : ProcessingUi

    /** Расшифровка готова и сохранена в историю под [entryId]. */
    data class Done(val entryId: String, val sourceName: String) : ProcessingUi

    data class Failed(val sourceName: String?, val message: String) : ProcessingUi
}

object ProcessingState {
    private val _models = MutableStateFlow<ModelsStatus>(ModelsStatus.Checking)
    val models: StateFlow<ModelsStatus> = _models.asStateFlow()

    private val _processing = MutableStateFlow<ProcessingUi>(ProcessingUi.Idle)
    val processing: StateFlow<ProcessingUi> = _processing.asStateFlow()

    fun setModels(s: ModelsStatus) { _models.value = s }
    fun setProcessing(s: ProcessingUi) { _processing.value = s }
    fun reset() { _processing.value = ProcessingUi.Idle }

    /** Сразу показывает экран обработки (до первого отчёта сервиса). */
    fun begin(sourceName: String) {
        _processing.value = ProcessingUi.Running(sourceName = sourceName)
    }

    fun markCancelling() {
        (_processing.value as? ProcessingUi.Running)?.let { _processing.value = it.copy(cancelling = true) }
    }
}
