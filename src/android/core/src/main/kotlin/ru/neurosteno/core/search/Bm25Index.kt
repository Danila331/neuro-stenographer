package ru.neurosteno.core.search

import kotlin.math.ln
import ru.neurosteno.core.summary.SummaryText

/**
 * BM25 по основам слов (порт `bench_rag/bm25.py`: k1 1.2, b 0.75, idf `ln(1 + (N - n + 0.5) / (n + 0.5))`). Строится в памяти из
 * сохранённых термов окон (повторная токенизация не нужна). Арифметика оценок -- float32 в том же порядке операций, что у эталона
 * (numpy 2), поэтому ранги при равных оценках совпадают.
 */
class Bm25Index(docTerms: List<List<String>>, private val k1: Double = 1.2, private val b: Double = 0.75, private val mode: String = "snow5") {
    private val n = docTerms.size
    private val tf: List<Map<String, Int>>
    private val dl: FloatArray
    private val avgdl: Float
    private val idf = HashMap<String, Double>()
    private val inv = HashMap<String, MutableList<Int>>()

    init {
        tf = docTerms.map { terms -> HashMap<String, Int>().also { m -> for (t in terms) m.merge(t, 1, Int::plus) } }
        dl = FloatArray(n) { tf[it].values.sum().toFloat() }
        var sum = 0.0
        for (x in dl) sum += x
        avgdl = if (n > 0) (sum.toFloat() / n.toFloat()) else 1f
        val df = HashMap<String, Int>()
        for (m in tf) for (t in m.keys) df.merge(t, 1, Int::plus)
        for ((t, d) in df) idf[t] = ln(1 + (n - d + 0.5) / (d + 0.5))
        for ((i, m) in tf.withIndex()) for (t in m.keys) inv.getOrPut(t) { ArrayList() }.add(i)
    }

    val size: Int get() = n

    /** Основы запроса (без слов вопроса), по которым ищем и которые подсвечивает UI. */
    fun queryTerms(query: String): List<String> = SummaryText.terms(query, dropQueryWords = true, mode = mode).distinct()

    fun scores(query: String): FloatArray {
        val s = FloatArray(n)
        val k1f = k1.toFloat()
        val bf = b.toFloat()
        val oneMinusB = (1 - b).toFloat()
        for (t in queryTerms(query)) {
            val w = idf[t] ?: continue
            for (i in inv.getValue(t)) {
                val f = tf[i].getValue(t)
                val num = (w * f * (k1 + 1)).toFloat()
                val den = f.toFloat() + k1f * (oneMinusB + bf * dl[i] / avgdl)
                s[i] += num / den
            }
        }
        return s
    }
}
