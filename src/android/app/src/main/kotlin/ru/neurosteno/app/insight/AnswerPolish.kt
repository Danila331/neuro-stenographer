package ru.neurosteno.app.insight

import java.util.Locale

/**
 * Постобработка текста ответа LLM (поиск, вопросы к записи): малые модели иногда повторяют предложение («Дата сегодня — 15 ноября.
 * [00:24, Говорящий 1] Дата сегодня — 15 ноября.»). Повторы удаляются, ссылки `[мм:сс, Говорящий N]` остаются у уцелевшего
 * экземпляра без дублей. Сравнение нормализованное (регистр, ё/е, пробелы, пунктуация, ссылки не учитываются); короткие предложения
 * (меньше [minWords] слов) не трогаем -- там повтор может быть осмысленным («Да. Да.»).
 */
object AnswerPolish {
    const val MIN_WORDS = 4

    private val cite = Regex("\\[\\d{1,2}(?::\\d{2}){1,2}\\s*,[^\\]]*\\]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Нормализованный ключ предложения без ссылок. */
    fun key(sentence: String): String =
        cite.replace(sentence, " ").lowercase(Locale.ROOT).replace('ё', 'е').replace(nonWord, " ").trim()

    fun wordCount(sentence: String): Int = key(sentence).split(' ').count { it.isNotEmpty() }

    /** Сегмент: предложение вместе со своими ссылками (до терминатора и сразу после него). */
    private class Segment(val raw: String, val extraCites: MutableList<String> = ArrayList())

    private fun segments(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        var start = 0
        var i = 0
        val n = text.length
        fun endsSentence(at: Int): Boolean = text[at] in ".!?…" && (at + 1 >= n || text[at + 1].isWhitespace() || text[at + 1] == '[' || text[at + 1] in "\"»)")
        while (i < n) {
            if (text[i] in ".!?…" && endsSentence(i)) {
                var j = i + 1
                while (j < n && text[j] in ".!?…") j++
                while (j < n && text[j] in "\"»)") j++
                // ссылки сразу после терминатора принадлежат этому предложению
                while (true) {
                    var k = j
                    while (k < n && text[k].isWhitespace()) k++
                    val m = cite.matchAt(text, k) ?: break
                    j = m.range.last + 1
                }
                out += Segment(text.substring(start, j).trim())
                while (j < n && text[j].isWhitespace()) j++
                start = j
                i = j
            } else i++
        }
        if (start < n) text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += Segment(it) }
        return out
    }

    /** Удаляет повторяющиеся предложения; текст без повторов возвращается как есть. */
    fun dedupe(text: String, minWords: Int = MIN_WORDS): String {
        if (text.isBlank()) return text
        val segs = segments(text)
        if (segs.size < 2) return text
        val kept = LinkedHashMap<String, Segment>()
        val order = ArrayList<Segment>()
        var removed = false
        for (s in segs) {
            val k = key(s.raw)
            if (k.isNotEmpty() && wordCount(s.raw) >= minWords && k in kept) {
                // повтор: его ссылки (которых ещё нет у оставшегося экземпляра) переносятся на оставшийся
                val first = kept.getValue(k)
                for (c in cite.findAll(s.raw).map { it.value }) {
                    if (c !in first.raw && c !in first.extraCites) first.extraCites += c
                }
                removed = true
                continue
            }
            if (k.isNotEmpty() && wordCount(s.raw) >= minWords) kept[k] = s
            order += s
        }
        if (!removed) return text
        return order.joinToString(" ") { s -> if (s.extraCites.isEmpty()) s.raw else s.raw + " " + s.extraCites.joinToString(" ") }
    }
}
