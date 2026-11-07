package ru.neurosteno.app.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.core.pdf.PdfDocModel
import ru.neurosteno.core.pdf.PdfSpeaker
import ru.neurosteno.core.pdf.PdfSummary
import ru.neurosteno.core.pdf.PdfSummaryItem
import ru.neurosteno.core.pdf.PdfSummaryTopic
import ru.neurosteno.core.pdf.PdfUtterance
import ru.neurosteno.core.summary.SummaryDoc

/** Собирает [PdfDocModel] из открытой расшифровки: имена говорящих, дата и длительность записи, выжимка и протокол (если уже составлены). */
object PdfModelBuilder {
    fun build(
        entry: HistoryEntry?, t: EditableTranscript, protocol: String?, title: String? = null,
        locale: Locale = Locale.forLanguageTag("ru"), summary: SummaryDoc? = null,
    ): PdfDocModel =
        PdfDocModel(
            title = title ?: entry?.title ?: "Расшифровка",
            dateText = entry?.createdAt?.takeIf { it > 0 }?.let { SimpleDateFormat("d MMMM yyyy, HH:mm", locale).format(Date(it)) } ?: "—",
            durationMs = entry?.durationMs?.takeIf { it > 0 } ?: t.durationMs,
            speakers = t.speakerIds.map { PdfSpeaker(it, t.displayName(it)) },
            utterances = t.utterances.map { PdfUtterance(it.speakerId, it.startMs, it.text) },
            protocol = protocol?.takeIf { it.isNotBlank() },
            summary = summary?.let { toPdfSummary(it, t) },
        )

    /** PDF только с выжимкой: шапка, легенда говорящих и сама выжимка (без расшифровки и протокола). */
    fun buildSummaryOnly(entry: HistoryEntry?, t: EditableTranscript, summary: SummaryDoc, locale: Locale = Locale.forLanguageTag("ru")): PdfDocModel =
        build(entry, t, protocol = null, title = "Выжимка: " + (entry?.title ?: "запись"), locale = locale, summary = summary).copy(includeTranscript = false)

    fun toPdfSummary(raw: SummaryDoc, t: EditableTranscript): PdfSummary = ru.neurosteno.app.insight.SummaryPolish.clean(raw).let { doc -> PdfSummary(
        llm = doc.mode == "llm",
        overview = doc.overview.trim().replace(Regex("\\s+"), " "),
        topics = doc.topics.map { topic ->
            PdfSummaryTopic(
                topic.title, topic.startMs, topic.endMs,
                topic.points.map { PdfSummaryItem(PdfSummaryItem.Kind.POINT, it.speaker, t.displayName(it.speaker), "", it.text, it.refs.minOfOrNull { r -> r.t }) } +
                    topic.decisions.map { PdfSummaryItem(PdfSummaryItem.Kind.DECISION, null, "", "", it.text, it.refs.minOfOrNull { r -> r.t }) } +
                    topic.tasks.map { PdfSummaryItem(PdfSummaryItem.Kind.TASK, null, "", it.who, it.text, it.refs.minOfOrNull { r -> r.t }) },
            )
        },
    ) }
}
