package ru.neurosteno.app.export

import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.ui.Format
import ru.neurosteno.core.summary.SummaryDoc

/** Выжимка (B50) в простой текст для TXT-экспорта: заголовок, «О чём», темы с диапазоном времени, пункты по говорящим, решения, задачи. */
object SummaryExport {
    fun toText(doc: SummaryDoc, t: EditableTranscript, entry: HistoryEntry?): String =
        toText(doc, names = { t.displayName(it) }, title = entry?.title ?: "запись", durationMs = entry?.durationMs?.takeIf { it > 0 } ?: t.durationMs, speakers = t.speakerIds.size)

    fun toText(raw: SummaryDoc, names: (String) -> String, title: String, durationMs: Long, speakers: Int): String = ru.neurosteno.app.insight.SummaryPolish.clean(raw).let { doc -> buildString {
        append("Выжимка: ").append(title).append('\n')
        append("Длительность: ").append(Format.clock(durationMs)).append(" · говорящих: ").append(speakers).append('\n')
        append(if (doc.mode == "llm") "ИИ-выжимка" else "Быстрая выжимка (без ИИ)").append(". Черновик — проверьте по репликам.\n")
        if (doc.overview.isNotBlank()) append("\nО чём:\n").append(doc.overview.trim().replace(Regex("\\s+"), " ")).append('\n')
        doc.topics.forEachIndexed { i, t ->
            append('\n').append(i + 1).append(". ").append(t.title).append(" (").append(Format.clock(t.startMs)).append('–').append(Format.clock(t.endMs)).append(")\n")
            fun tc(refs: List<ru.neurosteno.core.summary.SummaryRef>) = refs.minOfOrNull { it.t }?.let { " [${Format.clock(it)}]" }.orEmpty()
            t.points.forEach { append("  - ").append(names(it.speaker)).append(": ").append(it.text.trim()).append(tc(it.refs)).append('\n') }
            if (t.decisions.isNotEmpty()) {
                append("  Решения:\n")
                t.decisions.forEach { append("  * ").append(it.text.trim()).append(tc(it.refs)).append('\n') }
            }
            if (t.tasks.isNotEmpty()) {
                append("  Задачи:\n")
                t.tasks.forEach { append("  > ").append(if (it.who.isNotBlank()) it.who + ": " else "").append(it.text.trim()).append(tc(it.refs)).append('\n') }
            }
        }
    } }
}
