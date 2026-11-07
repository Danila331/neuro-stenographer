package ru.neurosteno.app.insight

import ru.neurosteno.core.summary.SummaryDecision
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryPoint
import ru.neurosteno.core.summary.SummaryRef
import ru.neurosteno.core.summary.SummaryTask
import ru.neurosteno.core.summary.SummaryTopic

/**
 * Показ выжимки без повторов (UI и экспорт; сам `summary.json` и движок не меняются -- паритет с эталоном цел): повторяющиеся предложения
 * в «О чём», одинаковые пункты одного говорящего, решения и задачи внутри темы схлопываются в один, ссылки на реплики объединяются.
 * Короткие пункты (меньше [MIN_ITEM_WORDS] слов) не трогаем.
 */
object SummaryPolish {
    const val MIN_ITEM_WORDS = 3

    fun clean(doc: SummaryDoc): SummaryDoc = doc.copy(overview = AnswerPolish.dedupe(doc.overview), topics = doc.topics.map(::cleanTopic))

    private fun mergeRefs(a: List<SummaryRef>, b: List<SummaryRef>): List<SummaryRef> = (a + b).distinctBy { it.n to it.t }

    private fun cleanTopic(t: SummaryTopic): SummaryTopic {
        val points = LinkedHashMap<String, SummaryPoint>()
        val shortPoints = ArrayList<SummaryPoint>()
        val order = ArrayList<Any>() // порядок: ключ длинного пункта или сам короткий
        for (p in t.points) {
            val k = p.speaker + "|" + AnswerPolish.key(p.text)
            if (AnswerPolish.wordCount(p.text) < MIN_ITEM_WORDS) { order += p; continue }
            val prev = points[k]
            if (prev == null) { points[k] = p; order += k } else points[k] = prev.copy(refs = mergeRefs(prev.refs, p.refs))
        }
        val mergedPoints = order.map { if (it is SummaryPoint) it else points.getValue(it as String) }

        val decisions = LinkedHashMap<String, SummaryDecision>()
        val dOrder = ArrayList<Any>()
        for (d in t.decisions) {
            val k = AnswerPolish.key(d.text)
            if (AnswerPolish.wordCount(d.text) < MIN_ITEM_WORDS) { dOrder += d; continue }
            val prev = decisions[k]
            if (prev == null) { decisions[k] = d; dOrder += k } else decisions[k] = prev.copy(refs = mergeRefs(prev.refs, d.refs))
        }
        val tasks = LinkedHashMap<String, SummaryTask>()
        val tOrder = ArrayList<Any>()
        for (x in t.tasks) {
            val k = AnswerPolish.key(x.who) + "|" + AnswerPolish.key(x.text)
            if (AnswerPolish.wordCount(x.text) < MIN_ITEM_WORDS) { tOrder += x; continue }
            val prev = tasks[k]
            if (prev == null) { tasks[k] = x; tOrder += k } else tasks[k] = prev.copy(refs = mergeRefs(prev.refs, x.refs))
        }
        return t.copy(
            points = mergedPoints,
            decisions = dOrder.map { if (it is SummaryDecision) it else decisions.getValue(it as String) },
            tasks = tOrder.map { if (it is SummaryTask) it else tasks.getValue(it as String) },
        )
    }
}
