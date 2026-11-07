package ru.neurosteno.app.llm

import ru.neurosteno.core.TextGuard

/**
 * Протокол по фрагментам с слиянием разделов в коде (BACKLOG B48, `protocol_mode = chunk_merge`). Порт 1:1 функций
 * `split_sections`, `merge_protocols`, `filter_quotes` из `src/eval/bench_llm/bench_llm/android_flow.py` (эталон llm-bench):
 * на длинной записи полнота фактов 0.69 против 0.19 у «конспект -> протокол». Сверяется с эталоном тестом на примерах,
 * сгенерированных самим Python-кодом (`app/tools/gen_llm_cases.py`).
 */
object ProtocolMerge {
    val SECTIONS = listOf("Резюме", "Вопросы", "Решения", "Поручения", "Цитаты")

    private val HEADING = Regex("^(Резюме|Вопросы|Решения|Поручения|Цитаты)\\s*:?\\s*(.*)$")
    private val QUOTE = Regex("«([^»]{8,})»")

    /** Python `str.splitlines()` для текста модели: делит по \n, \r\n, \r; завершающий перевод строки пустой строки не даёт. */
    private fun splitLines(text: String): List<String> {
        val parts = text.split(Regex("\r\n|\n|\r"))
        return if (parts.lastOrNull() == "") parts.dropLast(1) else parts
    }

    /** Разбор протокола на разделы; пункты без маркера «- » и текст после заголовка в одной строке сохраняются. */
    fun splitSections(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        SECTIONS.forEach { out[it] = ArrayList() }
        var cur: String? = null
        for (raw in splitLines(text)) {
            val line = raw.trim()
            val m = HEADING.matchEntire(line)
            if (m != null) {
                cur = m.groupValues[1]
                val rest = m.groupValues[2]
                if (rest.isNotEmpty()) out.getValue(cur).add(rest)
                continue
            }
            if (cur != null && line.isNotEmpty()) out.getValue(cur).add(line.trimStart('-', ' ').trim())
        }
        return out
    }

    /**
     * Слияние протоколов фрагментов: «Резюме» -- склейка резюме через пробел; остальные разделы -- конкатенация пунктов без
     * повторов (по нормализованному тексту) и без «нет»; пустой раздел -- «- нет».
     */
    fun mergeProtocols(parts: List<String>): String {
        val secs = parts.map(::splitSections)
        val lines = ArrayList<String>()
        val resume = secs.joinToString(" ") { it.getValue("Резюме").joinToString(" ").trim() }.trim()
        lines += "Резюме: $resume"
        for (name in SECTIONS.drop(1)) {
            val seen = HashSet<String>()
            val items = ArrayList<String>()
            for (s in secs) {
                for (it in s.getValue(name)) {
                    val k = TextGuard.norm(it)
                    if (k.isEmpty() || k == "нет" || !seen.add(k)) continue
                    items += it
                }
            }
            lines += "$name:"
            if (items.isEmpty()) lines += "- нет" else items.forEach { lines += "- $it" }
        }
        return lines.joinToString("\n")
    }

    /** Убирает пункты со знаком «- » и цитатой («...» от 8 символов), которой нет в расшифровке дословно (`transcriptNorm` -- результат [TextGuard.norm]). */
    fun filterQuotes(text: String, transcriptNorm: String): String {
        val keep = ArrayList<String>()
        for (line in splitLines(text)) {
            val m = QUOTE.find(line)
            if (m != null && line.trimStart().startsWith("-") && TextGuard.norm(m.groupValues[1]) !in transcriptNorm) continue
            keep += line
        }
        return keep.joinToString("\n")
    }

    /** Нормализованный текст всей расшифровки для [filterQuotes]. */
    fun normTranscript(utteranceTexts: List<String>): String = TextGuard.norm(utteranceTexts.joinToString(" "))
}
