package ru.neurosteno.core.summary

import kotlin.math.ln

/**
 * Выжимка без LLM (lite; также «первая версия» для show_extractive_first) -- порт `bench_rag/extractive.py`.
 * Те же фрагменты (долины), текст -- дословные реплики: заголовок = самые характерные основы фрагмента, пункты = по одной самой
 * содержательной реплике на говорящего, решения/задачи = реплики с маркерами.
 */
object ExtractiveSummary {
    private val DECISION_EXACT = setOf("итого", "согласен", "согласны", "единогласно", "значит", "фиксируем", "записываем")
    private val DECISION_PREFIX = listOf("решил", "решени", "договорил", "принят", "утвержд", "назнач")
    private val TASK_EXACT = setOf(
        "нужно", "надо", "подготовлю", "составлю", "сделаю", "займусь", "проверю", "закажу", "пришлю", "напишу", "свяжусь", "завтра",
    )
    private val TASK_PREFIX = listOf("должен")
    private val DAY_PREFIX = listOf("пятниц", "понедельник", "вторник", "сред", "четверг", "суббот", "воскресенья", "конца")
    private val K_PREFIX = listOf("пятниц", "завтра")

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Слова текста как `\w+` Python (lower, без ё->е: маркеры в эталоне регистронезависимы, ё не используют). */
    private fun words(text: String): List<String> {
        val s = text.lowercase()
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (isWordChar(s[i])) {
                var j = i
                while (j < s.length && isWordChar(s[j])) j++
                out += s.substring(i, j)
                i = j
            } else i++
        }
        return out
    }

    /** Эталон: `DECISION.search(text)` (слова-маркеры по границам слов). */
    fun isDecision(text: String): Boolean = words(text).any { w -> w in DECISION_EXACT || DECISION_PREFIX.any { w.startsWith(it) } }

    /** Эталон: `TASK.search(text)`, включая «до <день>…» и «к <пятниц|завтра>…» (два слова подряд, разделитель -- один пробел). */
    fun isTask(text: String): Boolean {
        if (words(text).any { w -> w in TASK_EXACT || TASK_PREFIX.any { w.startsWith(it) } }) return true
        val s = text.lowercase()
        return pairCue(s, "до", DAY_PREFIX) || pairCue(s, "к", K_PREFIX)
    }

    /** "<first> <prefix…>" как в регулярке `\bдо (?:…)\w*`: слово first, один пробел, слово с нужным началом. */
    private fun pairCue(s: String, first: String, prefixes: List<String>): Boolean {
        var from = 0
        while (true) {
            val i = s.indexOf("$first ", from)
            if (i < 0) return false
            val boundary = i == 0 || !isWordChar(s[i - 1])
            if (boundary) {
                var j = i + first.length + 1
                val st = j
                while (j < s.length && isWordChar(s[j])) j++
                val w = s.substring(st, j)
                if (prefixes.any { w.startsWith(it) }) return true
            }
            from = i + 1
        }
    }

    private fun capitalize(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1).lowercase()

    fun digest(utts: List<SUtt>, vecs: List<DoubleArray>?, targetChars: Int, seg: SegmenterConfig, k: Int = seg.k, mergeSim: Double = 0.6): List<Topic> {
        val frags = SummaryPlanner.plan(utts, vecs, targetChars, seg, k)
        val n = utts.size
        val uttTerms = utts.map { SummaryText.terms(it.text, mode = "snow5") }
        val df = HashMap<String, Int>()
        for (ts in uttTerms) for (w in ts.toSet()) df[w] = (df[w] ?: 0) + 1
        val out = ArrayList<Topic>()
        for ((lo, hi) in frags) {
            val tf = LinkedHashMap<String, Int>()
            for (i in lo until hi) for (w in uttTerms[i]) tf[w] = (tf[w] ?: 0) + 1
            val top = tf.entries.sortedBy { -it.value * ln(1 + n.toDouble() / df.getValue(it.key)) }.take(3).map { it.key }
            val forms = HashMap<String, String>()
            for (i in lo until hi) for (t in SummaryText.tokens(utts[i].text)) {
                val ts = SummaryText.terms(t, mode = "snow5")
                forms.putIfAbsent(ts.firstOrNull() ?: "", t)
            }
            val title = capitalize(top.joinToString(" ") { forms[it] ?: it }).ifEmpty { "Тема" }
            val t = Topic(title, lo, hi - 1)

            fun info(i: Int): Double = uttTerms[i].toSortedSet().sumOf { ln(1 + n.toDouble() / df.getValue(it)) }
            for (spk in (lo until hi).map { utts[it].spk }.toSortedSet()) {
                val ids = (lo until hi).filter { utts[it].spk == spk && utts[it].text.trim().split(Regex("\\s+")).size >= 3 }
                if (ids.isNotEmpty()) {
                    var b = ids[0]
                    var bi = info(b)
                    for (i in ids) { val v = info(i); if (v > bi) { b = i; bi = v } }
                    t.points += Point(spk, utts[b].text, listOf(b))
                }
            }
            for (i in lo until hi) {
                val tx = utts[i].text
                if (tx.trim().split(Regex("\\s+")).size < 3) continue
                if (isDecision(tx)) t.decisions += Item(tx, listOf(i))
                else if (isTask(tx)) t.tasks += Item(tx, listOf(i), "Говорящий ${utts[i].spk}")
            }
            t.decisions = t.decisions.take(3).toMutableList()
            t.tasks = t.tasks.take(3).toMutableList()
            out += t
        }
        return SummaryMerger.merge(out, mergeSim)
    }
}
