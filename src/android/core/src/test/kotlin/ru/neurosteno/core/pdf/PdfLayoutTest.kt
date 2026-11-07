package ru.neurosteno.core.pdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfLayoutTest {
    /** Фейк: ширина символа = 0.5 кегля (моно 0.6). Детерминированно и без Android. */
    private val metrics = PdfMetrics { text, font, size -> text.length * size * (if (font == PdfFont.MONO) 0.6f else 0.5f) }
    private val geo = PdfGeometry()
    private val speakers = listOf(PdfSpeaker("speaker_1", "Мурзик"), PdfSpeaker("speaker_2", "Говорящий 2"))

    private fun model(n: Int, protocol: String? = null, text: (Int) -> String = { "Реплика номер $it: добрый день, коллеги, начинаем заседание." }) =
        PdfDocModel(
            title = "Запись 2026-11-07", dateText = "7 ноября 2026, 08:25", durationMs = 125_000, speakers = speakers,
            utterances = (0 until n).map { PdfUtterance("speaker_${it % 2 + 1}", it * 4_000L, text(it)) }, protocol = protocol,
        )

    private fun PdfPage.texts() = ops.filterIsInstance<PdfOp.Text>()
    private fun List<PdfPage>.allText() = flatMap { it.texts() }.joinToString("\n") { it.text }

    @Test
    fun `short document fits one page with header legend transcript and numbering`() {
        val pages = PdfLayout.layout(model(3), metrics, geo)
        assertEquals(1, pages.size)
        val all = pages.allText()
        assertTrue(all.contains("Запись 2026-11-07"))
        assertTrue(all.contains("Дата: 7 ноября 2026, 08:25"))
        assertTrue(all.contains("Длительность: 02:05"))
        assertTrue(all.contains("Говорящих: 2"))
        assertTrue(all.contains("Обработано на устройстве"))
        assertTrue(all.contains("Мурзик (speaker_1)"))
        assertTrue(all.contains("[00:00]") && all.contains("[00:08]"))
        assertTrue(all.contains("Мурзик:") && all.contains("Говорящий 2:"))
        assertTrue(all.contains("Стр. 1 из 1"))
    }

    @Test
    fun `long transcript paginates, nothing leaves the content area, numbering is consistent`() {
        val pages = PdfLayout.layout(model(400), metrics, geo)
        assertTrue(pages.size > 5, "страниц ${pages.size}")
        pages.forEachIndexed { i, p ->
            assertTrue(p.texts().any { it.text == "Стр. ${i + 1} из ${pages.size}" }, "нет номера на стр. ${i + 1}")
            p.texts().filter { !it.text.startsWith("Стр.") && it.font != PdfFont.SMALL }.forEach {
                assertTrue(it.baseline <= geo.bottom + 0.5f, "текст ниже содержимого: ${it.baseline} > ${geo.bottom}")
                assertTrue(it.baseline >= geo.top, "текст выше содержимого")
                assertTrue(it.x >= geo.left - 0.01f && it.x + metrics.width(it.text, it.font, it.size) <= geo.right + 0.5f, "вышло за поля: '${it.text}'")
            }
        }
        // все 400 таймкодов присутствуют ровно по одному разу
        val tcs = pages.flatMap { it.texts() }.filter { it.font == PdfFont.MONO }
        assertEquals(400, tcs.size)
    }

    @Test
    fun `very long utterance wraps and splits across pages without losing text`() {
        val words = (1..2500).joinToString(" ") { "слово$it" }
        val pages = PdfLayout.layout(model(1) { words }, metrics, geo)
        assertTrue(pages.size >= 3, "страниц ${pages.size}")
        val body = pages.flatMap { it.texts() }.filter { it.font == PdfFont.BODY && it.x >= geo.left + 40f }.joinToString(" ") { it.text }
        val got = body.split(' ').filter { it.startsWith("слово") }
        assertEquals((1..2500).map { "слово$it" }, got)
        // таймкод печатается один раз -- на странице начала
        assertEquals(1, pages.flatMap { it.texts() }.count { it.font == PdfFont.MONO })
    }

    @Test
    fun `a single endless word is broken by characters inside the margins`() {
        val word = "а".repeat(900)
        val pages = PdfLayout.layout(model(1) { "начало $word конец" }, metrics, geo)
        val lines = pages.flatMap { it.texts() }.filter { it.font == PdfFont.BODY && it.x >= geo.left + 40f }
        lines.forEach { assertTrue(it.x + metrics.width(it.text, it.font, it.size) <= geo.right + 0.5f, "строка шире поля") }
        // все 900 букв длинного слова на месте (+2 «а» в слове «начало»)
        assertEquals(902, lines.joinToString("") { it.text }.count { it == 'а' })
    }

    @Test
    fun `no widow lines - split block leaves at least two lines on each page`() {
        val pages = PdfLayout.layout(model(300) { "строка " + "слово ".repeat(40) }, metrics, geo)
        // подсчёт строк реплик по страницам: ни у одной страницы реплика не начинается с единственной строки в самом низу
        pages.forEach { p ->
            val body = p.texts().filter { it.font == PdfFont.BODY && it.x >= geo.left + 40f }
            val lastTc = p.texts().lastOrNull { it.font == PdfFont.MONO }
            if (lastTc != null) {
                val after = body.filter { it.baseline >= lastTc.baseline - 0.5f }
                assertTrue(after.size >= 2 || p === pages.last(), "висячая строка на странице")
            }
        }
    }

    @Test
    fun `protocol is included only when present and keeps list structure`() {
        val without = PdfLayout.layout(model(2), metrics, geo).allText()
        assertFalse(without.contains("ПРОТОКОЛ"))
        val withBlank = PdfLayout.layout(model(2, protocol = "  \n "), metrics, geo).allText()
        assertFalse(withBlank.contains("ПРОТОКОЛ"))
        val proto = "Резюме: обсудили смету.\n\n**Решения:**\n- Утвердить смету\n- Назначить срок\n1. Первый пункт\n\n# Поручения\nМурзик — отчёт до пятницы."
        val pages = PdfLayout.layout(model(2, protocol = proto), metrics, geo)
        val all = pages.allText()
        assertTrue(all.contains("ПРОТОКОЛ"))
        assertTrue(all.contains("Утвердить смету") && all.contains("Назначить срок"))
        assertTrue(all.contains("•") && all.contains("1."))
        assertFalse(all.contains("**") || all.contains("# "))
        assertTrue(all.contains("Решения") && all.contains("Поручения"))
        // протокол идёт после расшифровки
        assertTrue(all.indexOf("РАСШИФРОВКА") < all.indexOf("ПРОТОКОЛ"))
    }

    @Test
    fun `long protocol paginates too`() {
        val proto = (1..200).joinToString("\n") { "- Пункт $it: " + "слово ".repeat(25) }
        val pages = PdfLayout.layout(model(2, protocol = proto), metrics, geo)
        assertTrue(pages.size >= 5)
        assertEquals(200, pages.flatMap { it.texts() }.count { it.text == "•" })
        pages.forEachIndexed { i, p -> assertTrue(p.texts().any { it.text == "Стр. ${i + 1} из ${pages.size}" }) }
    }

    @Test
    fun `speaker names with custom text are used in legend and lines, long names are clipped`() {
        val long = "Очень длинное имя говорящего " + "х".repeat(200)
        val m = model(2).copy(speakers = listOf(PdfSpeaker("speaker_1", long), PdfSpeaker("speaker_2", "Барсик")))
        val pages = PdfLayout.layout(m, metrics, geo)
        val all = pages.allText()
        assertTrue(all.contains("Барсик:"))
        assertTrue(all.contains("…:"), "длинное имя должно усекаться в строке реплики")
        pages.flatMap { it.texts() }.forEach { assertTrue(it.x + metrics.width(it.text, it.font, it.size) <= geo.right + 0.5f) }
    }

    @Test
    fun `empty transcript still produces a valid one-page document`() {
        val pages = PdfLayout.layout(model(0), metrics, geo)
        assertEquals(1, pages.size)
        assertTrue(pages.allText().contains("Стр. 1 из 1"))
    }

    @Test
    fun `heading is not left alone at the bottom of a page`() {
        // подбираем число реплик так, чтобы раздел «Расшифровка» оказался у самого низа первой страницы: заголовок обязан уйти вместе с текстом
        for (n in 1..30) {
            val pages = PdfLayout.layout(model(0).copy(utterances = (0 until n).map { PdfUtterance("speaker_1", it * 1000L, "x") }), metrics, geo)
            pages.forEach { p ->
                val h = p.texts().firstOrNull { it.font == PdfFont.HEADING && it.text == "РАСШИФРОВКА" } ?: return@forEach
                val below = p.texts().filter { it.font == PdfFont.MONO && it.baseline > h.baseline }
                assertTrue(below.isNotEmpty(), "заголовок без текста на странице (n=$n)")
            }
        }
    }

    @Test
    fun `wrap breaks on words and measures the first line separately`() {
        val m = PdfMetrics { t, _, s -> t.length * s }
        assertEquals(listOf("aa bb", "cc"), PdfLayout.wrap("aa bb cc", 50f, PdfFont.BODY, 10f, m))
        assertEquals(listOf("aa", "bb cc"), PdfLayout.wrap("aa bb cc", 50f, PdfFont.BODY, 10f, m, firstLineWidth = 30f))
        assertEquals(listOf(""), PdfLayout.wrap("   ", 50f, PdfFont.BODY, 10f, m))
        assertEquals(listOf("aaaaa", "aaaaa", "aa"), PdfLayout.wrap("aaaaaaaaaaaa", 50f, PdfFont.BODY, 10f, m))
    }

    @Test
    fun `clock formats minutes and hours`() {
        assertEquals("00:00", PdfLayout.clock(0))
        assertEquals("01:05", PdfLayout.clock(65_999))
        assertEquals("1:02:03", PdfLayout.clock(3_723_000))
    }

    @Test
    fun `protocol parser strips markdown and groups paragraphs`() {
        val b = ProtocolText.parse("**Итог**\nстрока один\nстрока два\n\n- пункт\n2) второй")
        assertEquals(
            listOf(
                ProtocolBlock.Paragraph("Итог строка один строка два"),
                ProtocolBlock.Item("•", "пункт"),
                ProtocolBlock.Item("2)", "второй"),
            ),
            b,
        )
        assertEquals(emptyList<ProtocolBlock>(), ProtocolText.parse(" \n\n "))
    }

    @Test
    fun `colon line closes the paragraph and empty trailing headings are dropped`() {
        val b = ProtocolText.parse("Резюме: обсудили повестку.\nВопросы:\n- один\nЦитаты:")
        assertEquals(
            listOf(
                ProtocolBlock.Paragraph("Резюме: обсудили повестку."),
                ProtocolBlock.Heading("Вопросы"),
                ProtocolBlock.Item("•", "один"),
            ),
            b,
        )
        assertEquals(emptyList<ProtocolBlock>(), ProtocolText.parse("Решения:\nЦитаты:"))
    }

    private fun summary(topics: Int, llm: Boolean = true, overview: String = "Обсудили закупку мисок и дежурство.") = PdfSummary(
        llm = llm, overview = overview,
        topics = (0 until topics).map { i ->
            PdfSummaryTopic(
                "Тема номер $i про мышей на складе", i * 20_000L, i * 20_000L + 18_000,
                listOf(
                    PdfSummaryItem(PdfSummaryItem.Kind.POINT, "speaker_1", "Мурзик", "", "Насчитали сорок семь мышей на складе", i * 20_000L + 2_000),
                    PdfSummaryItem(PdfSummaryItem.Kind.POINT, "speaker_2", "Говорящий 2", "", "Предложил назначить дежурного " + "и проверить склад ".repeat(8), i * 20_000L + 9_000),
                    PdfSummaryItem(PdfSummaryItem.Kind.DECISION, null, "", "", "Дежурной назначили Багиру", i * 20_000L + 12_000),
                    PdfSummaryItem(PdfSummaryItem.Kind.TASK, null, "", "Говорящий 2", "составить отчёт к утру", i * 20_000L + 15_000),
                    PdfSummaryItem(PdfSummaryItem.Kind.TASK, null, "", "", "проверить сметану", null),
                ),
            )
        },
    )

    @Test
    fun `summary section sits between legend and transcript with all item kinds and timecodes`() {
        val pages = PdfLayout.layout(model(3).copy(summary = summary(2)), metrics, geo)
        val all = pages.allText()
        assertTrue(all.contains("ВЫЖИМКА"))
        assertTrue(all.indexOf("ГОВОРЯЩИЕ") < all.indexOf("ВЫЖИМКА") && all.indexOf("ВЫЖИМКА") < all.indexOf("РАСШИФРОВКА"))
        assertTrue(all.contains("ИИ-выжимка. Черновик"))
        assertTrue(all.contains("Обсудили закупку мисок"))
        assertTrue(all.contains("Мурзик:") && all.contains("Решение:") && all.contains("Задача (Говорящий 2):") && all.contains("Задача:"))
        assertTrue(all.contains("00:00–00:18") && all.contains("00:20–00:38"))
        assertTrue(all.contains("[00:02]") || all.contains("[00:02]".trim()))
        pages.forEach { p -> p.texts().forEach { assertTrue(it.x + metrics.width(it.text, it.font, it.size) <= geo.right + 0.5f, "вышло за поля: '${it.text}'") } }
    }

    @Test
    fun `quick summary is labelled and omitted when absent`() {
        assertTrue(PdfLayout.layout(model(2).copy(summary = summary(1, llm = false)), metrics, geo).allText().contains("Быстрая выжимка (без ИИ)"))
        assertFalse(PdfLayout.layout(model(2), metrics, geo).allText().contains("ВЫЖИМКА"))
    }

    @Test
    fun `summary only document has no transcript or protocol and long summary paginates`() {
        val m = model(50, protocol = "Решения:\n- один").copy(summary = summary(60), includeTranscript = false)
        val pages = PdfLayout.layout(m, metrics, geo)
        val all = pages.allText()
        assertFalse(all.contains("РАСШИФРОВКА") || all.contains("ПРОТОКОЛ"))
        assertTrue(pages.size >= 4, "страниц ${pages.size}")
        pages.forEachIndexed { i, p ->
            assertTrue(p.texts().any { it.text == "Стр. ${i + 1} из ${pages.size}" })
            p.texts().filter { it.font != PdfFont.SMALL }.forEach { assertTrue(it.baseline <= geo.bottom + 0.5f) }
        }
        // заголовок темы не остаётся один внизу страницы: после него на той же странице есть строка пункта
        pages.forEach { p ->
            val heads = p.texts().filter { it.font == PdfFont.MONO && it.text.contains("–") }
            heads.forEach { h -> assertTrue(p.texts().any { it.baseline > h.baseline }, "заголовок темы без пунктов") }
        }
    }

    @Test
    fun `empty summary still prints heading and note without crashing`() {
        val pages = PdfLayout.layout(model(1).copy(summary = PdfSummary(false, "", emptyList())), metrics, geo)
        assertTrue(pages.allText().contains("ВЫЖИМКА"))
    }
}
