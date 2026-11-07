package ru.neurosteno.core.pdf

import java.util.Locale
import kotlin.math.max

/**
 * Раскладка PDF по страницам (без Android: текст измеряет [PdfMetrics], результат -- список примитивов [PdfOp]).
 *
 * Состав: шапка (рамка с названием, датой, длительностью, числом говорящих, «обработано на устройстве»), легенда говорящих,
 * реплики `[мм:сс] Имя: текст` (таймкод моно слева, имя жирным, текст с висячим отступом), протокол (если есть), нумерация
 * «Стр. i из N» в футере. Перенос по словам; слово длиннее строки режется по символам; заголовок раздела не остаётся один
 * внизу страницы; реплика не оставляет одну строку (висячие строки) -- кроме реплик длиннее страницы, они режутся как есть.
 */
object PdfLayout {
    private const val LH = 1.38f
    private const val BODY = 10f
    private const val SMALL = 8f
    private const val MONO = 9f
    private const val HEADING = 11.5f
    private const val TITLE = 18f
    private const val TC_COL = 54f
    private const val SWATCH = 8f

    fun layout(model: PdfDocModel, metrics: PdfMetrics, geo: PdfGeometry = PdfGeometry()): List<PdfPage> {
        val b = Builder(metrics, geo)
        b.header(model)
        b.legend(model)
        model.summary?.let { b.summary(it) }
        if (model.includeTranscript) {
            b.transcript(model)
            model.protocol?.takeIf { it.isNotBlank() }?.let { b.protocol(it) }
        }
        return b.finish(model.footerNote)
    }

    /** Время `мм:сс` или `ч:мм:сс` (как в TXT-экспорте). */
    fun clock(ms: Long): String {
        val s = max(0L, ms) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    /** Перенос по словам для одного шрифта; слово длиннее [maxWidth] режется по символам. Пустой текст -> одна пустая строка. */
    fun wrap(text: String, maxWidth: Float, font: PdfFont, size: Float, metrics: PdfMetrics, firstLineWidth: Float = maxWidth): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        var limit = firstLineWidth
        fun push() { lines += cur.toString(); cur = StringBuilder(); limit = maxWidth }
        for (w0 in words) {
            var w = w0
            while (true) {
                val candidate = if (cur.isEmpty()) w else "$cur $w"
                if (metrics.width(candidate, font, size) <= limit) { cur = StringBuilder(candidate); break }
                if (cur.isNotEmpty()) { push(); continue }
                // слово не помещается даже на пустой строке: режем по символам (минимум один символ -- прогресс гарантирован)
                var n = w.length
                while (n > 1 && metrics.width(w.substring(0, n), font, size) > limit) n--
                lines += w.substring(0, n)
                limit = maxWidth
                w = w.substring(n)
                if (w.isEmpty()) break
            }
        }
        if (cur.isNotEmpty()) lines += cur.toString()
        return lines
    }

    private class Line(val dx: Float, val pieces: List<Piece>)
    private class Piece(val text: String, val font: PdfFont, val size: Float, val dx: Float)

    private class Builder(val m: PdfMetrics, val g: PdfGeometry) {
        private val pages = ArrayList<List<PdfOp>>()
        private var ops = ArrayList<PdfOp>()
        private var y = g.top

        private fun lh(size: Float) = size * LH
        private fun newPage() { pages += ops; ops = ArrayList(); y = g.top }
        private fun atTop() = y <= g.top + 0.01f
        private fun ensure(h: Float) { if (y + h > g.bottom && !atTop()) newPage() }

        fun finish(note: String): List<PdfPage> {
            pages += ops
            val total = pages.size
            return pages.mapIndexed { i, p ->
                val base = g.height - g.margin + 2f
                val footer = listOf<PdfOp>(
                    PdfOp.Rule(g.left, base - SMALL - 4f, g.right, 0.6f),
                    PdfOp.Text(g.left, base, note, PdfFont.SMALL, SMALL),
                ) + run {
                    val label = "Стр. ${i + 1} из $total"
                    PdfOp.Text(g.right - m.width(label, PdfFont.SMALL, SMALL), base, label, PdfFont.SMALL, SMALL)
                }
                PdfPage(p + footer)
            }
        }

        // ---------------- шапка ----------------
        fun header(model: PdfDocModel) {
            val pad = 12f
            val inner = g.contentWidth - 2 * pad - 4f
            val titleLines = wrap(model.title.ifBlank { "Расшифровка" }, inner, PdfFont.TITLE, TITLE, m)
            val meta = listOf(
                "Дата: ${model.dateText}",
                "Длительность: ${clock(model.durationMs)}",
                "Говорящих: ${model.speakers.size}",
                "Обработано на устройстве: без интернета, данные остаются у вас",
            ).flatMap { wrap(it, inner, PdfFont.BODY, BODY, m) }
            val barH = 7f
            val h = barH + pad + titleLines.size * lh(TITLE) + 6f + meta.size * lh(BODY) + pad
            ensure(h)
            ops += PdfOp.Box(g.left, y, g.contentWidth - 4f, h, 2.2f, accentBar = true)
            var cy = y + barH + pad
            titleLines.forEach { cy += lh(TITLE); ops += PdfOp.Text(g.left + pad, cy - TITLE * 0.28f, it, PdfFont.TITLE, TITLE) }
            cy += 6f
            meta.forEach { cy += lh(BODY); ops += PdfOp.Text(g.left + pad, cy - BODY * 0.28f, it, PdfFont.BODY, BODY) }
            y += h + 18f
        }

        private fun sectionHeading(text: String, minContent: Float) {
            ensure(lh(HEADING) + 10f + minContent)
            y += lh(HEADING)
            ops += PdfOp.Text(g.left, y - HEADING * 0.28f, text.uppercase(Locale.ROOT), PdfFont.HEADING, HEADING)
            y += 4f
            ops += PdfOp.Rule(g.left, y, g.right, 1.8f)
            y += 8f
        }

        // ---------------- легенда ----------------
        fun legend(model: PdfDocModel) {
            if (model.speakers.isEmpty()) return
            sectionHeading("Говорящие", lh(BODY) + 4f)
            val textX = g.left + SWATCH + 8f
            model.speakers.forEach { sp ->
                val label = if (sp.name == sp.id) sp.name else "${sp.name} (${sp.id})"
                val lines = wrap(label, g.right - textX, PdfFont.BODY, BODY, m)
                ensure(lines.size * lh(BODY) + 4f)
                val top = y
                lines.forEachIndexed { i, l ->
                    y += lh(BODY)
                    ops += PdfOp.Text(textX, y - BODY * 0.28f, l, PdfFont.BODY, BODY)
                    if (i == 0) ops += PdfOp.Swatch(g.left, top + (lh(BODY) - SWATCH) / 2f, SWATCH, sp.id)
                }
                y += 4f
            }
            y += 10f
        }

        // ---------------- выжимка ----------------
        fun summary(sum: PdfSummary) {
            sectionHeading("Выжимка", 3 * lh(BODY))
            val note = if (sum.llm) "ИИ-выжимка. Черновик — проверьте по репликам." else "Быстрая выжимка (без ИИ): темы и цитаты реплик. Черновик — проверьте по репликам."
            wrap(note, g.contentWidth, PdfFont.SMALL, SMALL, m).forEach { y += lh(SMALL); ops += PdfOp.Text(g.left, y - SMALL * 0.28f, it, PdfFont.SMALL, SMALL) }
            y += 6f
            if (sum.overview.isNotBlank()) {
                val ls = wrap(sum.overview.trim(), g.contentWidth, PdfFont.BODY, BODY, m).map { Line(0f, listOf(Piece(it, PdfFont.BODY, BODY, 0f))) }
                placeBlock(ls, g.left, "", "", swatch = false)
                y += 8f
            }
            for (t in sum.topics) {
                val head = "${clock(t.startMs)}–${clock(t.endMs)}"
                val titleX = g.left + m.width("$head  ", PdfFont.MONO, MONO)
                val titleLines = wrap(t.title, g.right - titleX, PdfFont.BOLD, BODY + 0.5f, m)
                ensure(titleLines.size * lh(BODY) + 2 * lh(BODY)) // заголовок темы не остаётся один внизу страницы
                y += 4f
                titleLines.forEachIndexed { i, l ->
                    y += lh(BODY)
                    if (i == 0) ops += PdfOp.Text(g.left, y - BODY * 0.28f, head, PdfFont.MONO, MONO)
                    ops += PdfOp.Text(titleX, y - BODY * 0.28f, l, PdfFont.BOLD, BODY + 0.5f)
                }
                y += 3f
                val textX = g.left + 12f
                for (it in t.items) {
                    val label = when (it.kind) {
                        PdfSummaryItem.Kind.POINT -> "${it.speakerName}:"
                        PdfSummaryItem.Kind.DECISION -> "Решение:"
                        PdfSummaryItem.Kind.TASK -> if (it.who.isNotBlank()) "Задача (${it.who}):" else "Задача:"
                    }
                    val tail = it.tMs?.let { ms -> " [${clock(ms)}]" }.orEmpty()
                    val labelW = m.width("$label ", PdfFont.BOLD, BODY)
                    val parts = wrap(it.text.ifBlank { "…" } + tail, g.right - textX, PdfFont.BODY, BODY, m, firstLineWidth = max(g.right - textX - labelW, 10f))
                    val lines = parts.mapIndexed { i, p ->
                        if (i == 0) Line(0f, listOf(Piece(label, PdfFont.BOLD, BODY, 0f), Piece(p, PdfFont.BODY, BODY, labelW))) else Line(0f, listOf(Piece(p, PdfFont.BODY, BODY, 0f)))
                    }
                    placeBlock(lines, textX, "", it.speakerId.orEmpty(), swatch = it.kind == PdfSummaryItem.Kind.POINT && it.speakerId != null)
                    y += 3f
                }
                y += 5f
            }
            y += 8f
        }

        // ---------------- реплики ----------------
        fun transcript(model: PdfDocModel) {
            if (model.utterances.isEmpty()) return
            val names = model.speakers.associate { it.id to it.name }
            // первая реплика остаётся рядом с заголовком раздела (минимум две строки)
            sectionHeading("Расшифровка", 2 * lh(BODY))
            val textX = g.left + TC_COL
            for (u in model.utterances) {
                val name = (names[u.speakerId] ?: u.speakerId).let { n -> clip(n, (g.contentWidth - TC_COL) * 0.45f, PdfFont.BOLD) }
                val prefix = "$name:"
                val prefixW = m.width("$prefix ", PdfFont.BOLD, BODY)
                val lines = ArrayList<Line>()
                // первая строка: имя жирным + начало текста; дальше -- с висячим отступом под таймкод
                val first = wrapFirst(u.text, g.right - textX - prefixW, g.right - textX)
                first.forEachIndexed { i, t ->
                    lines += if (i == 0) Line(0f, listOf(Piece(prefix, PdfFont.BOLD, BODY, 0f), Piece(t, PdfFont.BODY, BODY, prefixW)))
                    else Line(0f, listOf(Piece(t, PdfFont.BODY, BODY, 0f)))
                }
                placeBlock(lines, textX, "[${clock(u.startMs)}]", u.speakerId)
                y += 5f
            }
        }

        private fun wrapFirst(text: String, firstWidth: Float, width: Float) =
            wrap(text.ifBlank { "…" }, width, PdfFont.BODY, BODY, m, firstLineWidth = max(firstWidth, 10f))

        private fun clip(s: String, maxW: Float, font: PdfFont): String {
            if (m.width(s, font, BODY) <= maxW) return s
            var n = s.length
            while (n > 1 && m.width(s.substring(0, n) + "…", font, BODY) > maxW) n--
            return s.substring(0, n) + "…"
        }

        /** Укладывает строки блока (с контролем висячих строк); таймкод и цвет рисуются у первой строки на странице начала. */
        private fun placeBlock(lines: List<Line>, textX: Float, timecode: String, speakerId: String, swatch: Boolean = timecode.isNotEmpty()) {
            var from = 0
            var firstChunk = true
            while (from < lines.size) {
                val remain = lines.size - from
                var fit = ((g.bottom - y) / lh(BODY)).toInt()
                if (fit >= remain) {
                    drawLines(lines, from, remain, textX, if (firstChunk) timecode else null, speakerId, firstChunk && swatch)
                    return
                }
                // блок не помещается целиком: делим так, чтобы на обеих страницах осталось не меньше двух строк
                var take = fit
                while (take > 0 && remain - take < 2) take--
                val fitsOnePage = remain * lh(BODY) <= g.contentHeight
                if (take < 2 && fitsOnePage && !atTop()) { newPage(); continue }
                if (take < 1) {
                    if (atTop()) take = max(1, fit) else { newPage(); continue }
                }
                drawLines(lines, from, take, textX, if (firstChunk) timecode else null, speakerId, firstChunk && swatch)
                from += take
                firstChunk = false
                newPage()
            }
        }

        private fun drawLines(lines: List<Line>, from: Int, count: Int, textX: Float, timecode: String?, speakerId: String, swatch: Boolean = !timecode.isNullOrEmpty()) {
            for (i in from until from + count) {
                val top = y
                y += lh(BODY)
                val base = y - BODY * 0.28f
                if (i == from && !timecode.isNullOrEmpty()) ops += PdfOp.Text(g.left, base, timecode, PdfFont.MONO, MONO)
                // цвет говорящего -- маркер в левом поле (скан глазами по странице)
                if (i == from && swatch) ops += PdfOp.Swatch(g.left - 11f, top + 2.5f, 6f, speakerId)
                for (p in lines[i].pieces) ops += PdfOp.Text(textX + p.dx, base, p.text, p.font, p.size)
            }
        }

        // ---------------- протокол ----------------
        fun protocol(text: String) {
            val blocks = ProtocolText.parse(text)
            if (blocks.isEmpty()) return
            y += 8f
            sectionHeading("Протокол", 3 * lh(BODY))
            for (blk in blocks) {
                when (blk) {
                    is ProtocolBlock.Heading -> {
                        val ls = wrap(blk.text, g.contentWidth, PdfFont.BOLD, BODY + 0.5f, m)
                        ensure(ls.size * lh(BODY) + 2 * lh(BODY)) // подзаголовок не остаётся один внизу
                        y += 3f
                        ls.forEach { y += lh(BODY); ops += PdfOp.Text(g.left, y - BODY * 0.28f, it, PdfFont.BOLD, BODY + 0.5f) }
                        y += 2f
                    }
                    is ProtocolBlock.Paragraph -> {
                        val ls = wrap(blk.text, g.contentWidth, PdfFont.BODY, BODY, m).map { Line(0f, listOf(Piece(it, PdfFont.BODY, BODY, 0f))) }
                        placeBlock(ls, g.left, "", "")
                        y += 4f
                    }
                    is ProtocolBlock.Item -> {
                        val indent = 16f
                        val ls = wrap(blk.text, g.contentWidth - indent, PdfFont.BODY, BODY, m).map { Line(0f, listOf(Piece(it, PdfFont.BODY, BODY, 0f))) }
                        placeItem(ls, blk.marker, indent)
                        y += 3f
                    }
                }
            }
        }

        private fun placeItem(ls: List<Line>, marker: String, indent: Float) {
            // как placeBlock, но маркер рисуется у первой строки пункта (на странице начала)
            var from = 0
            var first = true
            while (from < ls.size) {
                val remain = ls.size - from
                val fit = ((g.bottom - y) / lh(BODY)).toInt()
                val take = when {
                    fit >= remain -> remain
                    fit >= 1 -> fit
                    atTop() -> 1
                    else -> { newPage(); continue }
                }
                for (i in from until from + take) {
                    y += lh(BODY)
                    val base = y - BODY * 0.28f
                    if (i == 0 && first) ops += PdfOp.Text(g.left, base, marker, PdfFont.BODY, BODY)
                    ops += PdfOp.Text(g.left + indent, base, ls[i].pieces[0].text, PdfFont.BODY, BODY)
                }
                from += take
                first = false
                if (from < ls.size) newPage()
            }
        }
    }
}
