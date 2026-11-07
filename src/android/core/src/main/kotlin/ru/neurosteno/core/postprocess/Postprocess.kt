package ru.neurosteno.core.postprocess

import ru.neurosteno.core.Word

/** Постобработка слов ASR (секция `postprocess` конфига). */
object Postprocess {
    /**
     * `postprocess.drop_tiny_chunks`: весь вывод чанка не длиннее [maxChars] букв/цифр -- почти наверняка галлюцинация
     * («а», «э», «няу» на шуме и мяуканье). Знаки и пробелы не считаются.
     */
    fun isTinyChunk(text: String, maxChars: Int): Boolean = text.count { it.isLetterOrDigit() } <= maxChars

    /**
     * `postprocess.numbers = words`: цифры -> слова по-русски ([RussianNumbers.spell]). Слово из нескольких слов делит
     * свой интервал поровну; знак препинания, оставшийся отдельным куском, приклеивается к предыдущему слову.
     */
    fun spellNumbers(words: List<Word>): List<Word> {
        val out = ArrayList<Word>(words.size)
        for (w in words) {
            if (w.text.none { it.isDigit() }) {
                out += w
                continue
            }
            val parts = RussianNumbers.spell(w.text).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val merged = ArrayList<String>()
            for (p in parts) {
                if (merged.isNotEmpty() && p.none { it.isLetterOrDigit() }) merged[merged.size - 1] = merged.last() + p
                else merged += p
            }
            if (merged.size <= 1) {
                out += w.copy(text = merged.firstOrNull() ?: w.text)
                continue
            }
            val span = w.endMs - w.startMs
            merged.forEachIndexed { i, p ->
                val a = w.startMs + span * i / merged.size
                val b = if (i == merged.size - 1) w.endMs else w.startMs + span * (i + 1) / merged.size
                out += Word(p, a, maxOf(b, a + 1).coerceAtLeast(a), w.confidence)
            }
        }
        return out
    }

    private val DASH_AFTER_TERMINAL = Regex("(?<=[.?!\u2026])[\u2014\u2013]+")
    private val ANY_DASH = Regex("[\u2014\u2013]+")

    /**
     * `postprocess.strip_dashes` -- порт `refpipe/postprocess.py::strip_dashes_text`. off | after_terminal | all.
     * after_terminal: удалить серии длинных/средних тире (U+2014, U+2013) сразу после `.` `?` `!` `…`;
     * all: все такие тире. Дефис-минус (U+002D) не трогается никогда.
     */
    fun stripDashesText(text: String, mode: String): String = when (mode.lowercase()) {
        "after_terminal" -> DASH_AFTER_TERMINAL.replace(text, "")
        "all" -> ANY_DASH.replace(text, "")
        else -> text
    }

    /** Слова ASR после [spellNumbers] и до Aligner; опустевшее слово удаляется. */
    fun stripDashes(words: List<Word>, mode: String): List<Word> {
        if (mode.isBlank() || mode.lowercase() == "off") return words
        val out = ArrayList<Word>(words.size)
        for (w in words) {
            val t = stripDashesText(w.text, mode)
            if (t.isNotBlank()) out += if (t == w.text) w else w.copy(text = t)
        }
        return out
    }
}
