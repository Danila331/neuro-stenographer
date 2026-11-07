package ru.neurosteno.app.export

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.app.TestData
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.edit.EditableTranscript

class PdfModelBuilderTest {
    private val source = TestData.dialog()
    private val named = EditableTranscript.of(source, mapOf("speaker_2" to "Мурзик"))
    private val entry = HistoryEntry(
        id = "1", sourceName = "Совещание.wav", createdAt = 1_794_000_000_000, durationMs = 58_000,
        speakerCount = 2, utteranceCount = 3, preview = "",
    )

    @Test
    fun headerSpeakersAndUtterancesFromTranscript() {
        val m = PdfModelBuilder.build(entry, named, protocol = null, locale = Locale.forLanguageTag("ru"))
        assertEquals("Совещание", m.title)
        assertEquals(58_000, m.durationMs)
        assertTrue(m.dateText, m.dateText.contains("2026"))
        assertEquals(listOf("speaker_1", "speaker_2"), m.speakers.map { it.id })
        assertEquals("Мурзик", m.speakers[0].name) // пользовательское имя
        assertEquals("Говорящий 2", m.speakers[1].name) // имя по умолчанию
        assertEquals(3, m.utterances.size)
        assertEquals("добрый день", m.utterances[0].text)
        assertEquals(1_000, m.utterances[0].startMs)
    }

    @Test
    fun protocolOnlyWhenComposed() {
        assertNull(PdfModelBuilder.build(entry, named, protocol = null).protocol)
        assertNull(PdfModelBuilder.build(entry, named, protocol = "   \n").protocol)
        assertEquals("Итог", PdfModelBuilder.build(entry, named, protocol = "Итог").protocol)
    }

    @Test
    fun missingEntryFallsBackToTranscript() {
        val m = PdfModelBuilder.build(null, named, protocol = null)
        assertEquals("Расшифровка", m.title)
        assertEquals(named.durationMs, m.durationMs)
        assertEquals("—", m.dateText)
    }

    @Test
    fun pdfFormatIsDeclaredBinaryWithCorrectName() {
        assertEquals("pdf", ExportFormat.PDF.extension)
        assertEquals("application/pdf", ExportFormat.PDF.mimeType)
        assertTrue(ExportFormat.PDF.label.startsWith("PDF"))
        assertEquals("Совещание 1.pdf", Exports.fileName("Совещание 1.wav", ExportFormat.PDF))
    }
}
