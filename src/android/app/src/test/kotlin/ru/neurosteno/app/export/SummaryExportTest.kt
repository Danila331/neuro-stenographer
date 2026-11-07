package ru.neurosteno.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.app.TestData
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.core.pdf.PdfSummaryItem
import ru.neurosteno.core.summary.SummaryDecision
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryPoint
import ru.neurosteno.core.summary.SummaryRef
import ru.neurosteno.core.summary.SummaryTask
import ru.neurosteno.core.summary.SummaryTopic

class SummaryExportTest {
    private val doc = SummaryDoc(
        mode = "llm", done = true, overview = "Обсудили\n  смету и мышей.",
        topics = listOf(
            SummaryTopic(
                "Мыши на складе", 52_000, 118_000,
                points = listOf(SummaryPoint("speaker_2", "Насчитали 47 мышей", listOf(SummaryRef(21, 67_200, "speaker_2")))),
                decisions = listOf(SummaryDecision("Дежурной назначили Багиру", listOf(SummaryRef(37, 99_500, "speaker_1")))),
                tasks = listOf(
                    SummaryTask("Говорящий 2", "составить отчёт к утру", listOf(SummaryRef(40, 104_000, "speaker_2"))),
                    SummaryTask("", "проверить сметану", emptyList()),
                ),
            ),
            SummaryTopic("Пустая тема", 118_000, 120_000),
        ),
    )

    @Test
    fun textHasHeaderOverviewTopicsAndTimecodes() {
        val txt = SummaryExport.toText(doc, names = { if (it == "speaker_2") "Мурзик" else "Говорящий 1" }, title = "Совещание", durationMs = 125_000, speakers = 3)
        val lines = txt.lines()
        assertEquals("Выжимка: Совещание", lines[0])
        assertEquals("Длительность: 02:05 · говорящих: 3", lines[1])
        assertTrue(lines[2].startsWith("ИИ-выжимка. Черновик"))
        assertTrue(txt.contains("О чём:\nОбсудили смету и мышей.")) // пробелы сжаты
        assertTrue(txt.contains("1. Мыши на складе (00:52–01:58)"))
        assertTrue(txt.contains("  - Мурзик: Насчитали 47 мышей [01:07]"))
        assertTrue(txt.contains("  * Дежурной назначили Багиру [01:39]"))
        assertTrue(txt.contains("  > Говорящий 2: составить отчёт к утру [01:44]"))
        assertTrue(txt.contains("  > проверить сметану\n")) // без исполнителя и без ссылки
        assertTrue(txt.contains("2. Пустая тема (01:58–02:00)"))
    }

    @Test
    fun quickSummaryIsLabelledAndOverviewOmittedWhenBlank() {
        val txt = SummaryExport.toText(doc.copy(mode = "extractive", overview = ""), { it }, "x", 1_000, 1)
        assertTrue(txt.contains("Быстрая выжимка (без ИИ)"))
        assertFalse(txt.contains("О чём:"))
    }

    @Test
    fun pdfModelMapsSummaryWithNamesAndEarliestRef() {
        val named = EditableTranscript.of(TestData.dialog(), mapOf("speaker_2" to "Мурзик"))
        val ids = named.speakerIds
        val d = doc.copy(topics = listOf(doc.topics[0].copy(points = listOf(SummaryPoint(ids[0], "первым", listOf(SummaryRef(3, 9_000, ids[0]), SummaryRef(1, 1_000, ids[0])))))))
        val m = PdfModelBuilder.build(null, named, protocol = null, summary = d)
        val sum = m.summary!!
        assertTrue(sum.llm)
        assertEquals("Обсудили смету и мышей.", sum.overview)
        val items = sum.topics.single().items
        assertEquals(PdfSummaryItem.Kind.POINT, items[0].kind)
        assertEquals("Мурзик", items[0].speakerName) // пользовательское имя говорящего
        assertEquals(1_000L, items[0].tMs)           // самая ранняя ссылка
        assertEquals(listOf(PdfSummaryItem.Kind.POINT, PdfSummaryItem.Kind.DECISION, PdfSummaryItem.Kind.TASK, PdfSummaryItem.Kind.TASK), items.map { it.kind })
        assertEquals(null, items.last().tMs)
        assertEquals("Говорящий 2", items[2].who)
        assertTrue(m.includeTranscript)
        val only = PdfModelBuilder.buildSummaryOnly(null, named, d)
        assertFalse(only.includeTranscript)
        assertEquals(null, only.protocol)
        assertTrue(only.title.startsWith("Выжимка: "))
    }

    @Test
    fun exportFormatsForSummary() {
        assertEquals("Совещание 1.summary.txt", Exports.fileName("Совещание 1.wav", ExportFormat.SUMMARY_TXT))
        assertEquals("Совещание 1.summary.pdf", Exports.fileName("Совещание 1.wav", ExportFormat.SUMMARY_PDF))
        assertEquals("application/pdf", ExportFormat.SUMMARY_PDF.mimeType)
    }
}
