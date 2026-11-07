package ru.neurosteno.core.pdf

/** Блок текста протокола для печати. */
sealed interface ProtocolBlock {
    data class Heading(val text: String) : ProtocolBlock
    data class Paragraph(val text: String) : ProtocolBlock
    /** Пункт списка: [marker] ("•", "1.") печатается слева, [text] переносится с отступом. */
    data class Item(val marker: String, val text: String) : ProtocolBlock
}

/**
 * Разбор простого текста протокола LLM (списки, иногда markdown-разметка) в блоки для PDF. Маркеры `**`, `__`, `#`, `` ` ``
 * убираются: в печатном виде они только мусор. Пустая строка разделяет абзацы; соседние строки без маркера склеиваются в абзац.
 */
object ProtocolText {
    private val bullet = Regex("^[-*•–—]\\s+(.*)$")
    private val numbered = Regex("^(\\d{1,2}[.)])\\s+(.*)$")
    private val heading = Regex("^#{1,6}\\s*(.*)$")

    fun parse(text: String): List<ProtocolBlock> {
        val out = ArrayList<ProtocolBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += ProtocolBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) { flush(); continue }
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            when {
                h != null -> { flush(); clean(h.groupValues[1]).takeIf { it.isNotEmpty() }?.let { out += ProtocolBlock.Heading(it) } }
                b != null -> { flush(); clean(b.groupValues[1]).takeIf { it.isNotEmpty() }?.let { out += ProtocolBlock.Item("•", it) } }
                n != null -> { flush(); clean(n.groupValues[2]).takeIf { it.isNotEmpty() }?.let { out += ProtocolBlock.Item(n.groupValues[1], it) } }
                else -> {
                    val c = clean(line)
                    if (c.isEmpty()) continue
                    // короткая строка с двоеточием на конце («Решения:») -- подзаголовок; предыдущий абзац при этом закрывается
                    if (c.length <= 60 && c.endsWith(":")) { flush(); out += ProtocolBlock.Heading(c.removeSuffix(":")) }
                    else { if (para.isNotEmpty()) para.append(' '); para.append(c) }
                }
            }
        }
        flush()
        // подзаголовок без содержимого (последний или перед другим подзаголовком) на бумаге только мусор
        val kept = ArrayList<ProtocolBlock>()
        out.forEachIndexed { i, b ->
            val next = out.getOrNull(i + 1)
            if (b is ProtocolBlock.Heading && (next == null || next is ProtocolBlock.Heading)) return@forEachIndexed
            kept += b
        }
        return kept
    }

    private fun clean(s: String): String = s.replace("**", "").replace("__", "").replace("`", "").trim()
}
