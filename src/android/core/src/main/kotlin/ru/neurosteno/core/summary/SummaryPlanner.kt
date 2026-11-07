package ru.neurosteno.core.summary

/** Фрагменты, строки запроса и грамматика GBNF для одного вызова LLM -- порт `bench_rag/summary.py` (plan, line, grammar). */
object SummaryPlanner {
    fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
    }

    /** `[local] мм:сс Говорящий K: текст` -- локальный номер строки внутри фрагмента (с 1). */
    fun line(u: SUtt, local: Int): String = "[$local] ${fmtTime(u.startMs)} Говорящий ${u.spk}: ${u.text}"

    private fun codePoints(s: String) = s.codePointCount(0, s.length)

    /** Размер реплики в символах (Python `len`): строка с двузначным номером + перевод строки. */
    fun lineChars(u: SUtt): Int = codePoints(line(u, 99)) + 1

    /**
     * Фрагменты [lo, hi): по долинам косинуса, если есть векторы [vecs]; иначе нарезка по размеру [targetChars].
     */
    fun plan(utts: List<SUtt>, vecs: List<DoubleArray>?, targetChars: Int, seg: SegmenterConfig = SegmenterConfig(), k: Int = seg.k): List<Pair<Int, Int>> {
        val n = utts.size
        val chars = utts.map { lineChars(it) }
        val cuts: List<Int> = if (vecs != null) {
            TopicSegmenter.boundaries(vecs, chars, k, seg.alpha, seg.minUtts, targetChars, seg.minChars)
        } else {
            val c = ArrayList<Int>()
            var cur = 0
            for (i in 0 until n) {
                if (cur > 0 && cur + chars[i] > targetChars) { c += i; cur = 0 }
                cur += chars[i]
            }
            c
        }
        val edges = listOf(0) + cuts + n
        return (0 until edges.size - 1).map { edges[it] to edges[it + 1] }.filter { it.second > it.first }
    }

    private fun alt(nums: Iterable<Int>) = nums.joinToString(" | ") { "\"$it\"" }

    /**
     * GBNF одного фрагмента [lo, hi). Ссылки -- только существующие локальные номера; пункт говорящего K может цитировать
     * ТОЛЬКО строки K (`own_refs`): чужую реплику сгенерировать нельзя.
     */
    fun grammar(utts: List<SUtt>, lo: Int, hi: Int, maxStr: Int = 140, ownRefs: Boolean = true): String {
        val n = hi - lo
        val bySpk = java.util.TreeMap<Int, MutableList<Int>>()
        for (i in lo until hi) bySpk.getOrPut(utts[i].spk) { ArrayList() }.add(i - lo + 1)
        val pts = bySpk.keys.joinToString(" | ") { "point$it" }
        val rules = ArrayList<String>()
        for ((k, nums) in bySpk) {
            rules += "point$k ::= \"{\\\"speaker\\\":$k,\\\"refs\\\":[\" r$k (\",\" r$k){0,2} \"],\\\"text\\\":\" str \"}\""
            rules += "r$k ::= ${alt(if (ownRefs) nums else (1..n).toList())}"
        }
        val head = listOf(
            "root ::= \"{\\\"title\\\":\" str \",\\\"points\\\":[\" point (\",\" point){0,5} \"],\\\"decisions\\\":[\" (dec (\",\" dec){0,2})? \"],\\\"tasks\\\":[\" (task (\",\" task){0,2})? \"]}\"",
            "point ::= $pts",
            "dec ::= \"{\\\"refs\\\":[\" ref (\",\" ref){0,2} \"],\\\"text\\\":\" str \"}\"",
            "task ::= \"{\\\"refs\\\":[\" ref (\",\" ref){0,2} \"],\\\"who\\\":\" str \",\\\"text\\\":\" str \"}\"",
            "ref ::= ${alt(1..n)}",
            "str ::= \"\\\"\" [^\"\\\\\\n]{1,$maxStr} \"\\\"\"",
        )
        return (head + rules).joinToString("\n") + "\n"
    }

    /** ChatML Qwen3 без «размышлений» (пустой блок think), как `PromptBuilder.chatml`. */
    fun chatml(system: String, user: String): String =
        "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    /** Текст ответа модели с первой `{`. */
    fun extractJson(text: String): String {
        val i = text.indexOf('{')
        return if (i >= 0) text.substring(i) else text
    }
}
