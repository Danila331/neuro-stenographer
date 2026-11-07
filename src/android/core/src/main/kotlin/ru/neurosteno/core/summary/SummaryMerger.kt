package ru.neurosteno.core.summary

import kotlin.math.sqrt

/** Склейка тем соседних фрагментов и дедупликация -- порт `bench_rag/summary.py` (merge, _dedup, _sim). */
object SummaryMerger {
    fun sim(a: String, b: String): Double {
        val x = SummaryText.terms(a).toSet()
        val y = SummaryText.terms(b).toSet()
        val inter = x.count { it in y }
        val union = (x + y).size
        return inter.toDouble() / maxOf(1, union)
    }

    private fun <T> dedup(items: List<T>, key: (T) -> String): MutableList<T> {
        val out = ArrayList<T>()
        for (x in items) if (out.none { o -> sim(key(x), key(o)) >= 0.7 }) out += x
        return out
    }

    private fun cos(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (sqrt(na) * sqrt(nb) + 1e-9)
    }

    fun copy(t: Topic) = Topic(t.title, t.start, t.end, t.points.toMutableList(), t.decisions.toMutableList(), t.tasks.toMutableList(), t.centroid?.copyOf())

    /** Соседние фрагменты с похожими заголовками (или близкими центроидами, если задан [cos]) -- одна тема. Входные темы не изменяются. */
    fun merge(topics: List<Topic>, titleSim: Double = 0.45, cos: Double? = null): List<Topic> {
        val out = ArrayList<Topic>()
        for (src in topics.sortedBy { it.start }) {
            val t = copy(src)
            val prev = out.lastOrNull()
            val c1 = prev?.centroid
            val c2 = t.centroid
            if (prev != null && (sim(prev.title, t.title) >= titleSim || (cos != null && c1 != null && c2 != null && cos(c1, c2) >= cos))) {
                prev.end = maxOf(prev.end, t.end)
                prev.points += t.points
                prev.decisions += t.decisions
                prev.tasks += t.tasks
                if (c1 != null && c2 != null) prev.centroid = DoubleArray(c1.size) { (c1[it] + c2[it]) / 2 }
            } else out += t
        }
        for (t in out) {
            t.points = dedup(t.points) { it.text }
            t.decisions = dedup(t.decisions) { it.text }
            t.tasks = dedup(t.tasks) { it.text }
        }
        return out
    }
}
