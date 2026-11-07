package ru.neurosteno.app.insight

import android.content.Context
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.ProcessingService
import ru.neurosteno.app.summary.SummaryAvailability
import ru.neurosteno.app.summary.SummaryUi
import ru.neurosteno.core.Utterance

/**
 * Настоящий [SummaryRepository]: поверх `AppGraph.summary` (`SummaryRunner`). Запуск -- foreground-сервис
 * (`ProcessingService.summaryIntent`), прогресс и `doc` берутся из `SummaryRunner.ui`, сохранённая выжимка -- из `history/<id>/summary.json`.
 */
class GraphSummaryRepository(private val context: Context, private val graph: AppGraph) : SummaryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val states = ConcurrentHashMap<String, MutableStateFlow<SummaryState>>()

    override fun caps(): SummaryCaps {
        val cfg = graph.activeConfig()?.first?.summary
        val reason = (graph.summary.availability() as? SummaryAvailability.Unavailable)?.reason
        return SummaryCaps(llmMode = cfg?.usesLlm == true, llmUnavailableReason = reason)
    }

    override fun state(entryId: String): StateFlow<SummaryState> = states.getOrPut(entryId) {
        MutableStateFlow<SummaryState>(SummaryState.Loading).also { flow ->
            scope.launch { graph.summary.ui.collect { flow.value = map(entryId, it) } }
        }
    }

    private fun map(entryId: String, ui: SummaryUi): SummaryState {
        if (ui.entryId == entryId) {
            val doc = ui.doc
            when (ui.status) {
                SummaryUi.Status.Running -> return if (doc == null) SummaryState.Preparing(ui.stage.ifBlank { "Подготовка…" })
                else SummaryState.Shown(doc, SummaryProgress(ui.stage.ifBlank { "Уточняю выжимку…" }, ui.fragmentsDone, ui.fragments))
                SummaryUi.Status.Done -> if (doc != null) return SummaryState.Shown(doc, notice = ui.error)
                SummaryUi.Status.Cancelled -> if (doc != null) return SummaryState.Shown(doc, notice = "Остановлено: показана последняя готовая версия.")
                SummaryUi.Status.Failed -> return SummaryState.Failed(ui.error ?: "Не удалось составить выжимку.", doc ?: graph.summary.load(entryId))
                SummaryUi.Status.Idle -> {}
            }
        }
        return graph.summary.load(entryId)?.let { SummaryState.Shown(it) } ?: SummaryState.None
    }

    override fun build(entryId: String, utterances: List<Utterance>, useLlm: Boolean) {
        if (graph.summary.isRunning) return
        ContextCompat.startForegroundService(context, ProcessingService.summaryIntent(context, entryId))
    }

    override fun cancel(entryId: String) {
        context.startService(ProcessingService.cancelIntent(context))
    }
}
