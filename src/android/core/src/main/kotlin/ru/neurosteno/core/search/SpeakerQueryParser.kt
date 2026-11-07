package ru.neurosteno.core.search

import java.util.Locale
import ru.neurosteno.core.summary.SummaryText

/**
 * Ограничение по говорящему из вопроса: `speaker_2`, «говорящий 2», «второй спикер», пользовательские имена. [clean] -- запрос без
 * этих слов (его и ищем); [numbers] -- номера; [nameStems] -- основы слов каждого найденного отображаемого имени.
 */
data class SpeakerQuery(val clean: String, val numbers: Set<Int> = emptySet(), val nameStems: List<List<String>> = emptyList()) {
    val active: Boolean get() = numbers.isNotEmpty() || nameStems.isNotEmpty()
}

/** Порт `bench_rag/speaker_filter.py` (регулярные выражения Юникод-aware, как `re` в Python 3 для str). */
object SpeakerQueryParser {
    // Без \w \s \d \b и без (?U): на JVM они ASCII, в ICU (Android) -- Юникод; явные классы дают одинаковый результат (AndroidRegexGuardTest).
    private const val W = "[\\p{L}\\p{N}_]"
    private const val S = "[\\s\\p{Z}]"
    private const val D = "\\p{Nd}"
    private const val ROLE = "(?:говорящ$W*|спикер$W*|собеседник$W*|участник$W*|голос$W*)"
    private val ORD = mapOf("перв" to 1, "втор" to 2, "трет" to 3, "четв" to 4, "пят" to 5, "шест" to 6)
    private val WORDNUM = mapOf("один" to 1, "одного" to 1, "два" to 2, "двух" to 2, "три" to 3, "трех" to 3, "четыре" to 4, "пять" to 5)
    private val OPTS = setOf(RegexOption.IGNORE_CASE)

    private val PAT = listOf(
        Regex("speaker[_\\s\\p{Z}-]?($D+)", OPTS),
        Regex("$ROLE$S*(?:№|номер$W*|#)?$S*($D+)", OPTS),
        Regex("$ROLE$S*(?:номер$W*$S*)?(${WORDNUM.keys.joinToString("|")})(?!$W)", OPTS),
    )
    private val ORD_PAT = Regex("(?<!$W)(${ORD.keys.joinToString("|")})$W*$S+$ROLE", OPTS)
    private val NON_SPACE = Regex("[^\\s\\p{Z}]+")
    private val SPACES = Regex("$S+")

    private fun lower(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е')

    fun parse(query: String, displayNames: Collection<String>? = null): SpeakerQuery {
        var q = query
        val nums = LinkedHashSet<Int>()
        for (p in PAT) {
            for (m in p.findAll(q)) {
                val g = lower(m.groupValues[1])
                nums += g.toIntOrNull() ?: javaDigits(g) ?: WORDNUM.getValue(g)
            }
            q = p.replace(q, " ")
        }
        for (m in ORD_PAT.findAll(q)) {
            val key = lower(m.groupValues[1])
            nums += ORD[key.take(4)] ?: ORD.getValue(key)
        }
        q = ORD_PAT.replace(q, " ")
        val names = ArrayList<List<String>>()
        if (!displayNames.isNullOrEmpty()) {
            val qtok = SummaryText.tokens(q)
            val qstems = qtok.map { SummaryText.stemCached(it) }
            val drop = LinkedHashSet<String>()
            // эталон сортирует по длине имени (убыв.); при равной длине порядок множества Python не определён -- берём алфавитный
            for (nm in displayNames.toSortedSet().sortedByDescending { it.length }) {
                val ns = SummaryText.tokens(nm).map { SummaryText.stemCached(it) }
                if (ns.isNotEmpty() && ns[0] in qstems) {
                    names += ns
                    for ((i, s) in qstems.withIndex()) if (s in ns) drop += qtok[i]
                }
            }
            if (drop.isNotEmpty()) {
                q = NON_SPACE.findAll(q).map { it.value }.filter { t -> drop.none { d -> lower(t).contains(d) } }.joinToString(" ")
            }
        }
        return SpeakerQuery(SPACES.replace(q, " ").trim(), nums, names)
    }

    private fun javaDigits(s: String): Int? =
        if (s.isNotEmpty() && s.all { Character.isDigit(it) }) s.fold(0) { acc, c -> acc * 10 + Character.digit(c, 10) } else null

    /** Подходит ли реплика говорящего [speakerId] (в записи с именами [recordNames]) под ограничение. */
    fun matches(sq: SpeakerQuery, speakerId: String, recordNames: Map<String, String>): Boolean {
        if (!sq.active) return true
        val n = speakerId.substringAfter('_').toIntOrNull() ?: -1
        if (n in sq.numbers) return true
        val nm = recordNames[speakerId]
        if (!nm.isNullOrEmpty()) {
            val ns = SummaryText.tokens(nm).map { SummaryText.stemCached(it) }
            return ns.isNotEmpty() && sq.nameStems.any { it.isNotEmpty() && ns[0] == it[0] }
        }
        return false
    }
}
