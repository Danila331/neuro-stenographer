package ru.neurosteno.app.edit

/** Поиск по тексту расшифровки: без учёта регистра и ё/е (как в оценке WER). */
object SearchUtil {
    private fun fold(s: String): String = buildString(s.length) {
        for (ch in s) append(if (ch == 'ё' || ch == 'Ё') 'е' else ch.lowercaseChar())
    }

    /** Диапазоны вхождений [query] в [text] (индексы исходной строки; длина строки при сворачивании не меняется). */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        val t = fold(text)
        if (t.length != text.length) return emptyList() // экзотический регистр, меняющий длину -- не подсвечиваем
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = t.indexOf(q, from)
            if (i < 0) break
            out += i until i + q.length
            from = i + q.length
        }
        return out
    }

    /** Индексы реплик, где есть совпадение. */
    fun matchingIndices(texts: List<String>, query: String): List<Int> =
        if (query.isBlank()) emptyList() else texts.indices.filter { ranges(texts[it], query).isNotEmpty() }
}
