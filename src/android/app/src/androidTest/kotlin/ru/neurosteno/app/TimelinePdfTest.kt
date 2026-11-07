package ru.neurosteno.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neurosteno.app.export.PdfExporter
import ru.neurosteno.app.ui.components.SpeakerTimeline
import ru.neurosteno.app.ui.theme.NeuroTheme
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word
import ru.neurosteno.core.pdf.PdfDocModel
import ru.neurosteno.core.pdf.PdfFont
import ru.neurosteno.core.pdf.PdfOp
import ru.neurosteno.core.pdf.PdfSpeaker
import ru.neurosteno.core.pdf.PdfUtterance

/** Дорожка говорящих (тап/протяжка -> перемотка) и экспорт в PDF (настоящие шрифты, PdfRenderer читает файл). B54. */
@RunWith(AndroidJUnit4::class)
class TimelinePdfTest {
    @get:Rule val rule = createComposeRule()

    private fun utt(sp: Int, s: Long, e: Long) = Utterance("speaker_$sp", s, e, "т", listOf(Word("т", s, e)))
    private val utterances = listOf(utt(1, 0, 20_000), utt(2, 15_000, 40_000), utt(1, 45_000, 60_000))

    @Test
    fun tapSeeksProportionallyAndDragSeeksOnRelease() {
        val seeks = ArrayList<Long>()
        rule.setContent {
            NeuroTheme { SpeakerTimeline(utterances, highlighted = setOf(15_000L to 40_000L), durationMs = 60_000, positionMs = 10_000, onSeek = { seeks += it }) }
        }
        rule.onNodeWithTag("speaker_timeline").assertIsDisplayed()
        rule.onNodeWithTag("speaker_timeline").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        assertEquals(1, seeks.size)
        assertEquals(30_000.0, seeks[0].toDouble(), 1_500.0) // середина полосы = середина записи
        seeks.clear()
        rule.onNodeWithTag("speaker_timeline").performTouchInput { swipeRight(startX = width * 0.1f, endX = width * 0.75f) }
        assertTrue("после протяжки ровно одна перемотка: $seeks", seeks.size == 1)
        assertEquals(45_000.0, seeks[0].toDouble(), 3_000.0)
    }

    @Test
    fun disabledTimelineIgnoresTouches() {
        var n = 0
        rule.setContent { NeuroTheme { SpeakerTimeline(utterances, emptySet(), 60_000, 0, onSeek = { n++ }, enabled = false) } }
        rule.onNodeWithTag("speaker_timeline").performClick()
        assertEquals(0, n)
    }

    private fun longModel(): PdfDocModel = PdfDocModel(
        title = "Тестовая запись: совещание", dateText = "7 ноября 2026, 08:25", durationMs = 3_723_000,
        speakers = listOf(PdfSpeaker("speaker_1", "Мурзик"), PdfSpeaker("speaker_2", "Барсик"), PdfSpeaker("speaker_3", "Говорящий 3")),
        utterances = (0 until 160).map { PdfUtterance("speaker_${it % 3 + 1}", it * 23_000L, "Реплика $it: ставлю на голосование предложение о новой миске для общего зала, уточните, синий размер большой на два лица, а я не конкурсная.") },
        protocol = "Резюме: обсудили закупку миски.\n\nРешения:\n- Купить синюю миску с рыбками\n- Срок — до пятницы\n\nПоручения:\n1. Мурзик — оформить заказ\n2. Барсик — проверить бюджет",
    )

    @Test
    fun pdfIsWrittenWithRealFontsAndRendersNonBlankPages() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val exporter = PdfExporter(ctx)
        val model = longModel()
        val out = ByteArrayOutputStream()
        val pages = exporter.write(out, model)
        val bytes = out.toByteArray()
        assertTrue(pages >= 3)
        assertEquals("%PDF", String(bytes, 0, 4, Charsets.US_ASCII))
        // реальные метрики шрифтов: ни одна строка не выходит за поля
        val geo = ru.neurosteno.core.pdf.PdfGeometry()
        exporter.layout(model).forEach { p ->
            p.ops.filterIsInstance<PdfOp.Text>().forEach {
                assertTrue("строка '${it.text}' шире поля", it.x + exporter.metrics.width(it.text, it.font, it.size) <= geo.right + 1f)
            }
        }
        val f = File(ctx.cacheDir, "check.pdf").apply { writeBytes(bytes) }
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                assertEquals(pages, r.pageCount)
                for (i in listOf(0, r.pageCount - 1)) r.openPage(i).use { page ->
                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE) }
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var dark = 0
                    for (x in 0 until bmp.width step 3) for (y in 0 until bmp.height step 3) if (android.graphics.Color.red(bmp.getPixel(x, y)) < 128) dark++
                    assertTrue("страница $i пустая (dark=$dark)", dark > 300)
                    if (i == 0) File(ctx.getExternalFilesDir(null), "pdf_page1.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        // кириллица реально в документе (строки идут в Type3/шрифт): минимум -- размер значительно больше пустого документа
        assertTrue(bytes.size > 20_000)
    }
}
