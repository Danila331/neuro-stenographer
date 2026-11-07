package ru.neurosteno.app.insight

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.neurosteno.core.Utterance

/**
 * Фейковый [SummaryRepository] (debug и тесты): быстрая выжимка сразу, ИИ-версия «готовится» по шагам и заменяет её.
 * Режимы -- [FakeInsightConfig]. Хранит состояние только в памяти.
 */
class FakeSummaryRepository(private val scope: CoroutineScope, private val names: (String) -> String = { it }) : SummaryRepository {
    private val states = ConcurrentHashMap<String, MutableStateFlow<SummaryState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    override fun caps() = SummaryCaps(FakeInsightConfig.summaryLlmMode, FakeInsightConfig.summaryLlmReason)

    override fun state(entryId: String): StateFlow<SummaryState> = flow(entryId)

    private fun flow(id: String) = states.getOrPut(id) { MutableStateFlow(SummaryState.None) }

    override fun build(entryId: String, utterances: List<Utterance>, useLlm: Boolean) {
        jobs.remove(entryId)?.cancel()
        val st = flow(entryId)
        val quick = FakeSummaryBuilder.extractive(utterances)
        if (quick.isEmpty) { st.value = SummaryState.Failed("В записи нет реплик для выжимки."); return }
        val caps = caps()
        if (!(useLlm && caps.canRunLlm)) {
            // lite (extractive) или ИИ недоступен: быстрая версия и пояснение, почему ИИ-версии не будет
            st.value = SummaryState.Shown(quick, notice = caps.llmUnavailableReason?.takeIf { caps.llmMode })
            return
        }
        val total = quick.topics.size.coerceAtLeast(1)
        fun progress(i: Int) = SummaryProgress(if (i == 0) "Загрузка модели…" else "Фрагмент $i из $total", i, total)
        st.value = SummaryState.Shown(quick.copy(fragments = total), progress(0))
        jobs[entryId] = scope.launch {
            for (i in 1..total) {
                while (isActive && FakeInsightConfig.summaryHoldWorking) delay(200)
                if (FakeInsightConfig.summaryStepMs > 0) delay(FakeInsightConfig.summaryStepMs)
                if (!isActive) return@launch
                if (i < total) st.value = SummaryState.Shown(quick.copy(fragments = total, fragmentsDone = i), progress(i))
            }
            st.value = SummaryState.Shown(FakeSummaryBuilder.llm(quick, names))
        }
    }

    override fun cancel(entryId: String) {
        jobs.remove(entryId)?.cancel()
        (flow(entryId).value as? SummaryState.Shown)?.let { flow(entryId).value = SummaryState.Shown(it.doc, notice = "Остановлено: показана быстрая выжимка.") }
    }
}
