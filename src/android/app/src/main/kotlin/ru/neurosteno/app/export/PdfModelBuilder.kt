package ru.neurosteno.app.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.core.pdf.PdfDocModel
import ru.neurosteno.core.pdf.PdfSpeaker
import ru.neurosteno.core.pdf.PdfUtterance

/** Собирает [PdfDocModel] из открытой расшифровки: имена говорящих, дата и длительность записи, протокол (если он уже составлен). */
object PdfModelBuilder {
    fun build(entry: HistoryEntry?, t: EditableTranscript, protocol: String?, title: String? = null, locale: Locale = Locale.forLanguageTag("ru")): PdfDocModel =
        PdfDocModel(
            title = title ?: entry?.title ?: "Расшифровка",
            dateText = entry?.createdAt?.takeIf { it > 0 }?.let { SimpleDateFormat("d MMMM yyyy, HH:mm", locale).format(Date(it)) } ?: "—",
            durationMs = entry?.durationMs?.takeIf { it > 0 } ?: t.durationMs,
            speakers = t.speakerIds.map { PdfSpeaker(it, t.displayName(it)) },
            utterances = t.utterances.map { PdfUtterance(it.speakerId, it.startMs, it.text) },
            protocol = protocol?.takeIf { it.isNotBlank() },
        )
}
