package ru.neurosteno.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.core.PipelineResult
import ru.neurosteno.core.Stage

/** Состояние обработки: единственный источник правды для UI, сервис пишет, Activity читает. */
sealed interface ProcessingUi {
    data object Idle : ProcessingUi
    data class Running(val stage: Stage, val stageFraction: Float, val overall: Float) : ProcessingUi
    data class Done(val result: PipelineResult, val sourceName: String) : ProcessingUi
    data class Failed(val message: String) : ProcessingUi
}

object ProcessingState {
    private val _models = MutableStateFlow<ModelsStatus>(ModelsStatus.Checking)
    val models: StateFlow<ModelsStatus> = _models.asStateFlow()

    private val _processing = MutableStateFlow<ProcessingUi>(ProcessingUi.Idle)
    val processing: StateFlow<ProcessingUi> = _processing.asStateFlow()

    fun setModels(s: ModelsStatus) { _models.value = s }
    fun setProcessing(s: ProcessingUi) { _processing.value = s }
    fun reset() { _processing.value = ProcessingUi.Idle }
}
