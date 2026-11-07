package ru.neurosteno.app.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream
import androidx.compose.ui.graphics.toArgb
import ru.neurosteno.app.R
import ru.neurosteno.app.ui.theme.AccentConfig
import ru.neurosteno.app.ui.theme.speakerFill
import ru.neurosteno.core.pdf.PdfDocModel
import ru.neurosteno.core.pdf.PdfFont
import ru.neurosteno.core.pdf.PdfGeometry
import ru.neurosteno.core.pdf.PdfLayout
import ru.neurosteno.core.pdf.PdfMetrics
import ru.neurosteno.core.pdf.PdfOp
import ru.neurosteno.core.pdf.PdfPage

/**
 * Экспорт в PDF нативным [PdfDocument] (без сети и сторонних библиотек). Шрифты -- вшитые вариативные Unbounded / Golos Text /
 * JetBrains Mono (с кириллицей): ресурсы копируются в кэш один раз, начертание задаётся осью `wght`. Раскладку страниц делает
 * чистый [PdfLayout] (тестируется на JVM), здесь только измерение и рисование. Печатный стиль: белая бумага, чёрный текст,
 * акцент -- только полоса в рамке шапки, цвета говорящих -- маленькие маркеры.
 */
class PdfExporter(private val context: Context) {
    private val geometry = PdfGeometry()

    private class Fonts(val typefaces: Map<PdfFont, Typeface>) {
        val paints: Map<PdfFont, Paint> = typefaces.mapValues { (_, tf) -> Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = tf; color = Color.BLACK } }
    }

    private val fonts: Fonts by lazy {
        val dir = File(context.cacheDir, "pdf-fonts").apply { mkdirs() }
        fun face(res: Int, name: String, weight: Int): Typeface {
            val f = File(dir, "$name.ttf")
            if (!f.isFile || f.length() == 0L) context.resources.openRawResource(res).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            return Typeface.Builder(f).setFontVariationSettings("'wght' $weight").build()
        }
        Fonts(
            mapOf(
                // заголовок -- Golos 800, а не Unbounded: у Type3-вывода вариативного Unbounded ломается ToUnicode (поиск/копирование названия)
                PdfFont.TITLE to face(R.font.golos_text, "golos", 800),
                PdfFont.HEADING to face(R.font.unbounded, "unbounded", 700),
                PdfFont.BODY to face(R.font.golos_text, "golos", 400),
                PdfFont.BOLD to face(R.font.golos_text, "golos", 700),
                PdfFont.MONO to face(R.font.jetbrains_mono, "jbmono", 500),
                PdfFont.SMALL to face(R.font.golos_text, "golos", 400),
            ),
        )
    }

    /** Измеритель с теми же шрифтами, которыми рисуем (иначе переносы не совпадут с рендером). */
    val metrics = PdfMetrics { text, font, size ->
        val p = fonts.paints.getValue(font)
        p.textSize = size
        p.measureText(text)
    }

    /** Раскладка без записи в файл (для проверок и предпросмотра). */
    fun layout(model: PdfDocModel): List<PdfPage> = PdfLayout.layout(model, metrics, geometry)

    /** Пишет PDF в [out]; возвращает число страниц. */
    fun write(out: OutputStream, model: PdfDocModel): Int {
        val pages = layout(model)
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { i, page ->
                val info = PdfDocument.PageInfo.Builder(geometry.width.toInt(), geometry.height.toInt(), i + 1).create()
                val p = doc.startPage(info)
                draw(p.canvas, page)
                doc.finishPage(p)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        return pages.size
    }

    private fun draw(c: Canvas, page: PdfPage) {
        c.drawColor(Color.WHITE)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
        for (op in page.ops) when (op) {
            is PdfOp.Text -> {
                val p = fonts.paints.getValue(op.font)
                p.textSize = op.size
                c.drawText(op.text, op.x, op.baseline, p)
            }
            is PdfOp.Rule -> {
                stroke.strokeWidth = op.thickness
                c.drawLine(op.x1, op.y, op.x2, op.y, stroke)
            }
            is PdfOp.Swatch -> {
                fill.style = Paint.Style.FILL
                fill.color = speakerFill(op.speakerId).toArgb()
                c.drawRect(op.x, op.y, op.x + op.size, op.y + op.size, fill)
                stroke.strokeWidth = 0.7f
                c.drawRect(op.x, op.y, op.x + op.size, op.y + op.size, stroke)
            }
            is PdfOp.Box -> {
                val shadow = 3.5f
                fill.style = Paint.Style.FILL
                fill.color = Color.BLACK
                c.drawRect(op.x + shadow, op.y + shadow, op.x + op.w + shadow, op.y + op.h + shadow, fill) // жёсткая тень
                fill.color = Color.WHITE
                c.drawRect(op.x, op.y, op.x + op.w, op.y + op.h, fill)
                if (op.accentBar) {
                    fill.color = AccentConfig.DEFAULT.color.toArgb()
                    c.drawRect(op.x, op.y, op.x + op.w, op.y + 7f, fill)
                    stroke.strokeWidth = op.border
                    c.drawLine(op.x, op.y + 7f, op.x + op.w, op.y + 7f, stroke)
                }
                stroke.strokeWidth = op.border
                c.drawRect(op.x, op.y, op.x + op.w, op.y + op.h, stroke)
            }
        }
    }
}
