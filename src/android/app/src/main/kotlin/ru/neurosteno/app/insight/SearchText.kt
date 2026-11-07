package ru.neurosteno.app.insight

import java.util.Locale

/** Подсветка совпадений в тексте фрагмента: слово подсвечивается, если начинается с основы слова запроса. */
object Highlight {
    private val word = Regex("[\\p{L}\\p{N}]+")

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е')

    /** Диапазоны [from, toExclusive) слов [text], совпавших с основами [terms] (основы короче двух символов игнорируются). */
    fun ranges(text: String, terms: List<String>): List<IntRange> {
        val t = terms.map(::norm).filter { it.length >= 2 }.distinct()
        if (t.isEmpty()) return emptyList()
        return word.findAll(text).filter { m -> val w = norm(m.value); t.any { w.startsWith(it) } }.map { it.range }.toList()
    }
}

/** Разбор ссылок `[мм:сс, Говорящий N]` в тексте ответа ИИ. */
object CitationParser {
    sealed interface Part {
        data class Text(val text: String) : Part
        data class Cite(val raw: String, val citation: SearchCitation) : Part
    }

    private val cite = Regex("\\[(\\d{1,2}(?::\\d{2}){1,2})\\s*,\\s*([^\\]]+?)\\s*\\]")
    private val speaker = Regex("(?:Говорящий|speaker_?)\\s*(\\d+)", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Part> {
        val out = ArrayList<Part>()
        var pos = 0
        for (m in cite.findAll(text)) {
            if (m.range.first > pos) out += Part.Text(text.substring(pos, m.range.first))
            val ms = clockToMs(m.groupValues[1])
            val sp = speaker.find(m.groupValues[2])?.groupValues?.get(1)?.let { "speaker_$it" }
            out += Part.Cite(m.value, SearchCitation(ms, sp))
            pos = m.range.last + 1
        }
        if (pos < text.length) out += Part.Text(text.substring(pos))
        return out
    }

    fun citations(text: String): List<SearchCitation> = parse(text).filterIsInstance<Part.Cite>().map { it.citation }

    /** `мм:сс` или `ч:мм:сс` -> мс. */
    fun clockToMs(s: String): Long {
        val p = s.split(':').map { it.toLong() }
        return when (p.size) { 3 -> (p[0] * 3600 + p[1] * 60 + p[2]) * 1000; 2 -> (p[0] * 60 + p[1]) * 1000; else -> 0 }
    }

    /**
     * Запись, на которую указывает ссылка: фрагмент с репликой того же говорящего вблизи этого времени (время в ссылке -- с точностью до секунды);
     * иначе единственная запись среди [hits], иначе [fallbackRecordId].
     */
    fun resolveRecord(c: SearchCitation, hits: List<SearchHit>, fallbackRecordId: String?): String? {
        val exact = hits.firstOrNull { h -> h.lines.any { it.startMs / 1000 == c.startMs / 1000 && (c.speakerId == null || it.speakerId == c.speakerId) } }
        if (exact != null) return exact.recordId
        val near = hits.firstOrNull { c.startMs / 1000 in (it.startMs / 1000)..(it.endMs / 1000 + 1) }
        if (near != null) return near.recordId
        return hits.map { it.recordId }.distinct().singleOrNull() ?: fallbackRecordId
    }

    /** Ответ-отказ по промпту: «В найденных фрагментах этого нет.» */
    fun isRefusal(text: String): Boolean = text.trim().trimEnd('.', '!').equals("В найденных фрагментах этого нет", ignoreCase = true) ||
        text.trim().startsWith("В найденных фрагментах этого нет")
}

/** Основы слов запроса для подсветки и фейкового поиска: без слов-вопросов и служебных, укороченные до основы (≤ 5 символов). */
object QueryTerms {
    private val stop = setOf(
        "что", "как", "кто", "где", "когда", "про", "для", "при", "над", "под", "это", "было", "были", "есть", "говорил", "говорила", "говорили", "говорит",
        "сказал", "сказала", "обсуждали", "обсуждали", "тема", "теме", "тему", "записи", "запись", "истории", "говорящий", "говорящего", "говорящему",
        "спикер", "какие", "какой", "какая", "решили", "решение", "приняли", "упоминал", "упоминали",
    )

    fun of(query: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase(Locale.ROOT).replace('ё', 'е')).map { it.value }
        .filter { it !in stop && !it.all(Char::isDigit) && it.length >= 3 }
        .map { stem(it) }.distinct().toList()

    private fun stem(w: String): String = when {
        w.length >= 7 -> w.take(5)
        w.length >= 5 -> w.take(w.length - 2)
        else -> w.take(w.length - 1)
    }
}
