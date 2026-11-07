package ru.neurosteno.app.insight

import ru.neurosteno.core.Utterance
import ru.neurosteno.core.summary.SummaryDecision
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryPoint
import ru.neurosteno.core.summary.SummaryRef
import ru.neurosteno.core.summary.SummaryTask
import ru.neurosteno.core.summary.SummaryTopic

/**
 * Режимы фейков для отладки и скриншотов (debug-сборка переключает их через `DebugInsightReceiver`). В тестах -- свои значения.
 * Все поля читаются в момент запроса, поэтому смена режима действует на следующую операцию.
 */
object FakeInsightConfig {
    @Volatile var summaryLlmMode: Boolean = true
    @Volatile var summaryLlmReason: String? = null
    /** Шаг имитации прогресса ИИ-выжимки, мс (0 -- мгновенно; в UI-тестах). */
    @Volatile var summaryStepMs: Long = 900
    /** Остановиться на «ИИ-выжимка готовится…» (для скриншота состояния). */
    @Volatile var summaryHoldWorking: Boolean = false
    @Volatile var searchIndexBuilding: Boolean = false
    /** normal | refuse | unavailable | disabled */
    @Volatile var searchAnswerMode: String = "normal"
    @Volatile var searchStepMs: Long = 40

    fun reset() {
        summaryLlmMode = true; summaryLlmReason = null; summaryStepMs = 900; summaryHoldWorking = false
        searchIndexBuilding = false; searchAnswerMode = "normal"; searchStepMs = 40
    }
}

/**
 * Построитель фейковой выжимки из настоящей расшифровки (ссылки на реплики всегда валидны): темы -- последовательные группы реплик,
 * заголовок -- частые слова, пункты -- по реплике на говорящего, решения и задачи -- по маркерным словам. Это НЕ порт движка,
 * а правдоподобные данные для UI и тестов.
 */
object FakeSummaryBuilder {
    private val decisionMarkers = Regex("решил|решени|утвержд|согласи|принят|единогласно", RegexOption.IGNORE_CASE)
    private val taskMarkers = Regex("нужно|надо|поручаю|подготов|отчёт|отчет|до пятницы|до конца|составь|проведём|проведем", RegexOption.IGNORE_CASE)
    private val stop = setOf("это", "что", "как", "так", "для", "или", "уже", "если", "вот", "тоже", "нас", "мне", "его", "они", "там", "тут", "всё", "все", "был", "была", "было", "будет", "очень", "можно", "только", "когда", "потому", "почему")

    private fun ref(utts: List<Utterance>, i: Int) = SummaryRef(i + 1, utts[i].startMs, utts[i].speakerId)

    /** Быстрая (extractive) выжимка: мгновенно, цитаты реплик. */
    fun extractive(utts: List<Utterance>, groupSize: Int = 6): SummaryDoc {
        if (utts.isEmpty()) return SummaryDoc(mode = SUMMARY_MODE_EXTRACTIVE)
        val topics = utts.indices.chunked(groupSize).filter { it.isNotEmpty() }.map { idx ->
            val slice = idx.map { utts[it] }
            val words = slice.flatMap { u -> Regex("[а-яёa-z]{4,}", RegexOption.IGNORE_CASE).findAll(u.text.lowercase()).map { it.value }.toList() }
                .filter { it !in stop }
            val top = words.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3).map { it.key }
            val title = top.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { "Обсуждение" }
            val points = idx.groupBy { utts[it].speakerId }.map { (sp, ids) ->
                val i = ids.first()
                SummaryPoint(sp, utts[i].text.trim(), listOf(ref(utts, i)))
            }
            val decisions = idx.filter { decisionMarkers.containsMatchIn(utts[it].text) }.take(2).map { SummaryDecision(utts[it].text.trim(), listOf(ref(utts, it))) }
            val tasks = idx.filter { taskMarkers.containsMatchIn(utts[it].text) && it !in decisions.mapNotNull { d -> d.refs.firstOrNull()?.n?.minus(1) } }
                .take(2).map { SummaryTask("", utts[it].text.trim(), listOf(ref(utts, it))) }
            SummaryTopic(title, slice.first().startMs, slice.last().endMs, points, decisions, tasks)
        }
        return SummaryDoc(mode = SUMMARY_MODE_EXTRACTIVE, topics = topics)
    }

    /** «ИИ-версия» для фейка: пересказанные пункты, исполнители задач и обзор. Структура и ссылки те же. */
    fun llm(quick: SummaryDoc, names: (String) -> String = { it }): SummaryDoc {
        val topics = quick.topics.map { t ->
            t.copy(
                points = t.points.map { p -> p.copy(text = retell(p.text)) },
                decisions = t.decisions.map { it.copy(text = retell(it.text)) },
                tasks = t.tasks.map { it.copy(who = names(it.refs.firstOrNull()?.speaker ?: ""), text = retell(it.text)) },
            )
        }
        val overview = if (topics.isEmpty()) "" else "Разговор о том, как " + topics.take(3).joinToString(", ") { it.title.lowercase() } + "; приняты решения по ключевым вопросам."
        return quick.copy(mode = SUMMARY_MODE_LLM, model = "fake-qwen3-1.7b", done = true, fragments = topics.size, fragmentsDone = topics.size, overview = overview, topics = topics)
    }

    private fun retell(s: String): String = s.trim().trimEnd('.', '!', '?', '…').let { it.replaceFirstChar { c -> c.uppercase() } }
}
