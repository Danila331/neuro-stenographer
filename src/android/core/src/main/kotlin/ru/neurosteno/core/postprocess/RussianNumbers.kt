package ru.neurosteno.core.postprocess

import java.math.BigInteger
import java.util.regex.Pattern

/**
 * «Цифры -> слова» по-русски -- порт `stenoeval.textnorm.spell_numbers` (num2words ru): всегда именительный падеж,
 * порядковые -- мужской род, десятичная дробь «три запятая пять», числа длиннее 18 цифр читаются по цифрам.
 * Отличие от эталона: порядковые числа считаются до 999 999 (круглые тысячи -- «тысячный», остальное --
 * количественным), чего хватает для речи.
 */
object RussianNumbers {
    private val UNITS = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val TEENS = listOf(
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val TENS = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val HUNDREDS = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")

    // (ед., 2-4, 5+) ; род: тысяча -- женский
    private class Scale(val one: String, val few: String, val many: String, val feminine: Boolean)

    private val SCALES = listOf(
        null,
        Scale("тысяча", "тысячи", "тысяч", true),
        Scale("миллион", "миллиона", "миллионов", false),
        Scale("миллиард", "миллиарда", "миллиардов", false),
        Scale("триллион", "триллиона", "триллионов", false),
        Scale("квадриллион", "квадриллиона", "квадриллионов", false),
        Scale("квинтиллион", "квинтиллиона", "квинтиллионов", false),
    )

    private val ORD_UNITS = listOf("нулевой", "первый", "второй", "третий", "четвертый", "пятый", "шестой", "седьмой", "восьмой", "девятый")
    private val ORD_TEENS = listOf(
        "десятый", "одиннадцатый", "двенадцатый", "тринадцатый", "четырнадцатый", "пятнадцатый",
        "шестнадцатый", "семнадцатый", "восемнадцатый", "девятнадцатый",
    )
    private val ORD_TENS = listOf("", "", "двадцатый", "тридцатый", "сороковой", "пятидесятый", "шестидесятый", "семидесятый", "восьмидесятый", "девяностый")
    private val ORD_HUNDREDS = listOf("", "сотый", "двухсотый", "трехсотый", "четырехсотый", "пятисотый", "шестисотый", "семисотый", "восьмисотый", "девятисотый")

    private val ORD_SUFFIXES = "й ый ий ой я ая яя ое ее е ые ие го ого его му ому ему м ом ем ым им ых их ю ую юю ей ой".split(' ').toSet()

    // Unicode-версия \w (как в Python 3) задана явно: флаг (?U)/UNICODE_CHARACTER_CLASS не поддерживается регулярками Android (ICU) --
    // «Invalid value for match mode flags», и object падал при инициализации на устройстве (настройка «Числа: словами»).
    private const val W = "[\\p{L}\\p{N}_]"
    private val NUM_RE: Pattern = Pattern.compile(
        "(?<!$W)(\\d+)(?:[.,](\\d+))?(?:(-?)([а-яё]{1,4}))?(?!$W)",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
    )

    fun spell(text: String): String {
        val m = NUM_RE.matcher(text)
        val sb = StringBuilder()
        var last = 0
        while (m.find()) {
            sb.append(text, last, m.start())
            sb.append(repl(m.group(1)!!, m.group(2), m.group(3), (m.group(4) ?: "").lowercase()))
            last = m.end()
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    private fun repl(whole: String, frac: String?, hy: String?, suffixIn: String): String {
        var suffix = suffixIn
        if (!hy.isNullOrEmpty() && suffix !in ORD_SUFFIXES && suffix.length <= 2) suffix = ""
        if (!frac.isNullOrEmpty()) {
            val fw = if (frac.startsWith("0")) digits(frac) else cardinal(frac)
            return " ${cardinal(whole)} запятая $fw $suffix "
        }
        if (suffix in ORD_SUFFIXES) return " ${ordinal(whole)} "
        return " ${cardinal(whole)} $suffix "
    }

    private fun digits(s: String) = s.map { UNITS[it - '0'] }.joinToString(" ")

    fun cardinal(digitsStr: String): String {
        val n = digitsStr.trimStart('0').ifEmpty { "0" }
        if (n.length > 18) return digits(n)
        val out = cardinalBig(BigInteger(n))
        // в живой речи «тысяча девятьсот...», а не «одна тысяча девятьсот...»
        return if (out.startsWith("одна тысяча")) out.removePrefix("одна ") else out
    }

    private fun cardinalBig(n: BigInteger): String {
        if (n.signum() == 0) return UNITS[0]
        val parts = ArrayList<String>()
        var rest = n
        val groups = ArrayList<Int>()
        while (rest.signum() > 0) {
            groups += rest.mod(BigInteger.valueOf(1000)).toInt()
            rest = rest.divide(BigInteger.valueOf(1000))
        }
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            val scale = SCALES[i]
            parts += triple(g, scale?.feminine ?: false)
            if (scale != null) parts += plural(g, scale)
        }
        return parts.joinToString(" ")
    }

    private fun plural(g: Int, s: Scale): String {
        val t = g % 100
        val u = g % 10
        return when {
            t in 11..19 -> s.many
            u == 1 -> s.one
            u in 2..4 -> s.few
            else -> s.many
        }
    }

    private fun triple(g: Int, feminine: Boolean): String {
        val words = ArrayList<String>()
        val h = g / 100
        val t = g % 100
        if (h > 0) words += HUNDREDS[h]
        if (t in 10..19) {
            words += TEENS[t - 10]
        } else {
            if (t / 10 >= 2) words += TENS[t / 10]
            val u = t % 10
            if (u > 0) words += if (feminine && u == 1) "одна" else if (feminine && u == 2) "две" else UNITS[u]
        }
        return words.joinToString(" ")
    }

    fun ordinal(digitsStr: String): String {
        val n = digitsStr.trimStart('0').ifEmpty { "0" }
        if (n.length > 6) return cardinal(n)
        val v = n.toInt()
        if (v == 0) return ORD_UNITS[0]
        if (v == 1000) return "тысячный"
        val last: String
        val head: Int
        when {
            v % 100 != 0 -> {
                val t = v % 100
                last = when {
                    t < 10 -> ORD_UNITS[t]
                    t < 20 -> ORD_TEENS[t - 10]
                    t % 10 == 0 -> ORD_TENS[t / 10]
                    else -> ORD_UNITS[t % 10]
                }
                head = if (t < 20 || t % 10 == 0) v - t else v - t % 10
            }
            v % 1000 != 0 -> {
                last = ORD_HUNDREDS[(v % 1000) / 100]
                head = v - v % 1000
            }
            else -> return cardinal(n)
        }
        val prefix = if (head > 0) cardinal(head.toString()) + " " else ""
        return prefix + last
    }
}
