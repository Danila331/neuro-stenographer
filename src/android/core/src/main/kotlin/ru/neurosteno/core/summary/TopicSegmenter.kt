package ru.neurosteno.core.summary

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Нарезка расшифровки на темы по «долинам» косинуса между окнами реплик (TextTiling) -- порт `bench_rag/segment.py`.
 * Векторы -- эмбеддинги реплик (те же, что в индексе поиска) либо лексические TF-IDF ([lexicalVectors]) без модели.
 */
object TopicSegmenter {
    /** sim[g] = косинус между средним k реплик до границы g и среднего k реплик после (граница g -- между g и g+1). */
    fun gapSims(vecs: List<DoubleArray>, k: Int): DoubleArray {
        val n = vecs.size
        val out = DoubleArray(maxOf(0, n - 1)) { 1.0 }
        for (g in 0 until n - 1) {
            val a = mean(vecs, maxOf(0, g - k + 1), g + 1)
            val b = mean(vecs, g + 1, minOf(n, g + 1 + k))
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            out[g] = dot / (sqrt(na) * sqrt(nb) + 1e-9)
        }
        return out
    }

    private fun mean(vecs: List<DoubleArray>, from: Int, to: Int): DoubleArray {
        val d = vecs[0].size
        val m = DoubleArray(d)
        for (i in from until to) for (j in 0 until d) m[j] += vecs[i][j]
        val c = (to - from).toDouble()
        for (j in 0 until d) m[j] /= c
        return m
    }

    /** Индексы реплик, с которых начинается новый сегмент (без 0). [chars] -- размер реплик в символах для ограничений. */
    fun boundaries(
        vecs: List<DoubleArray>, chars: List<Int>, k: Int = 3, alpha: Double = 0.5, minUtts: Int = 3, maxChars: Int = 2400, minChars: Int = 300,
    ): List<Int> {
        val n = vecs.size
        if (n < 2 * minUtts) return emptyList()
        val s = gapSims(vecs, k)
        val mean = s.average()
        val std = sqrt(s.sumOf { (it - mean) * (it - mean) } / s.size)
        val thr = mean - alpha * std
        val cand = (0 until n - 1).filter { g ->
            s[g] < thr && (g == 0 || s[g] <= s[g - 1]) && (g == n - 2 || s[g] <= s[g + 1])
        }
        val cuts = ArrayList<Int>()
        for (g in cand.sortedBy { s[it] }) { // сначала самые глубокие долины; минимальный размер сегмента сохраняем
            val pts = (cuts + (g + 1)).sorted()
            val edges = listOf(0) + pts + n
            var ok = true
            for (i in 0 until edges.size - 1) {
                val a = edges[i]
                val b = edges[i + 1]
                if (b - a < minUtts || (a until b).sumOf { chars[it] } < minChars) { ok = false; break }
            }
            if (ok) cuts += g + 1
        }
        cuts.sort()
        val out = HashSet<Int>()
        var start = 0
        for (end in cuts + n) {
            out += splitLong(s, chars, start, end, maxChars)
            if (end < n) out += end
            start = end
        }
        return out.sorted()
    }

    private fun splitLong(s: DoubleArray, chars: List<Int>, a: Int, b: Int, maxChars: Int): List<Int> {
        if ((a until b).sumOf { chars[it] } <= maxChars || b - a < 4) return emptyList()
        val lo = a + 1
        val hi = b - 2
        val g = if (hi >= lo) (lo..hi).minByOrNull { if (it - 1 < s.size) s[it - 1] else 1.0 }!! else (a + b) / 2
        return splitLong(s, chars, a, g, maxChars) + g + splitLong(s, chars, g, b, maxChars)
    }

    /** TF-IDF по основам реплик одной записи -- запасная «близость» без эмбеддера (эталон: `lexical_vectors`). */
    fun lexicalVectors(texts: List<String>): List<DoubleArray> {
        val docs = texts.map { t ->
            val c = LinkedHashMap<String, Int>()
            for (w in SummaryText.terms(t, mode = "snow5")) c[w] = (c[w] ?: 0) + 1
            c
        }
        val df = LinkedHashMap<String, Int>()
        for (d in docs) for (w in d.keys) df[w] = (df[w] ?: 0) + 1
        val vocab = HashMap<String, Int>()
        for (w in df.keys) vocab[w] = vocab.size
        val n = texts.size
        val size = maxOf(1, vocab.size)
        return docs.map { d ->
            val v = DoubleArray(size)
            for ((w, f) in d) v[vocab.getValue(w)] = (1 + ln(f.toDouble())) * ln(1 + n.toDouble() / df.getValue(w))
            v
        }
    }
}
