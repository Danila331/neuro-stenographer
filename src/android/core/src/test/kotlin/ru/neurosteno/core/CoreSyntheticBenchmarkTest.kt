package ru.neurosteno.core

import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.neurosteno.core.diar.SpeakerRelabeler
import ru.neurosteno.core.summary.TopicSegmenter

/** Воспроизводимый baseline горячих алгоритмов без моделей и сети. */
class CoreSyntheticBenchmarkTest {
    private fun vectors(n: Int, d: Int, seed: Int): Array<DoubleArray> {
        val rnd = Random(seed)
        return Array(n) {
            val v = DoubleArray(d) { rnd.nextDouble(-1.0, 1.0) }
            val norm = sqrt(v.sumOf { it * it }) + 1e-9
            DoubleArray(d) { v[it] / norm }
        }
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    @Test
    fun syntheticBaseline() {
        val config = RelabelConfig()
        val relabelVectors = vectors(400, 192, 42)
        val durations = LongArray(400) { 1_000L + (it % 17) * 100L }
        val relabelTimes = ArrayList<Long>()
        var labels = IntArray(0)
        repeat(2) { SpeakerRelabeler.labelUnits(relabelVectors, durations, config) }
        repeat(4) {
            val t0 = System.nanoTime()
            labels = SpeakerRelabeler.labelUnits(relabelVectors, durations, config)
            relabelTimes += (System.nanoTime() - t0) / 1_000_000
        }

        val topicVectors = vectors(5_000, 384, 43).asList()
        val topicTimes = ArrayList<Long>()
        var sims = DoubleArray(0)
        repeat(2) { TopicSegmenter.gapSims(topicVectors, 3) }
        repeat(5) {
            val t0 = System.nanoTime()
            sims = TopicSegmenter.gapSims(topicVectors, 3)
            topicTimes += (System.nanoTime() - t0) / 1_000_000
        }
        val report = buildString {
            appendLine("relabel_units=400x192")
            appendLine("relabel_median_ms=${median(relabelTimes)}")
            appendLine("relabel_all_ms=${relabelTimes.joinToString(",")}")
            appendLine("relabel_labels_hash=${labels.joinToString(",").hashCode()}")
            appendLine("topic_boundaries=5000x384")
            appendLine("topic_median_ms=${median(topicTimes)}")
            appendLine("topic_all_ms=${topicTimes.joinToString(",")}")
            appendLine("topic_sims_hash=${sims.joinToString(",") .hashCode()}")
        }
        File("build/benchmarks/core-baseline.txt").apply { parentFile.mkdirs(); writeText(report) }
        print(report)
        assertTrue(relabelTimes.isNotEmpty() && topicTimes.isNotEmpty())
    }

    @Test
    fun relabelDistanceMatrixShouldNotBeRecomputedForEachCandidateK() {
        val vectors = vectors(400, 192, 42)
        val durations = LongArray(400) { 1_000L + (it % 17) * 100L }
        val times = ArrayList<Long>()
        repeat(4) { SpeakerRelabeler.labelUnits(vectors, durations, RelabelConfig()) }
        repeat(10) {
            val t0 = System.nanoTime()
            SpeakerRelabeler.labelUnits(vectors, durations, RelabelConfig())
            times += (System.nanoTime() - t0) / 1_000_000
        }
        val median = median(times.takeLast(5))
        assertTrue(median < 50, "Ожидалось устранение повторных матриц расстояний: median=${median}ms, все=$times")
    }

    @Test
    fun topicGapSimsShouldStayExactAndReduceLargeWindowCost() {
        val vectors = vectors(5_000, 384, 43).asList()
        fun reference(k: Int): DoubleArray {
            val out = DoubleArray(vectors.size - 1) { 1.0 }
            for (g in 0 until vectors.size - 1) {
                fun mean(from: Int, to: Int): DoubleArray {
                    val m = DoubleArray(vectors[0].size)
                    for (i in from until to) for (j in m.indices) m[j] += vectors[i][j]
                    val count = (to - from).toDouble()
                    for (j in m.indices) m[j] /= count
                    return m
                }
                val a = mean(maxOf(0, g - k + 1), g + 1)
                val b = mean(g + 1, minOf(vectors.size, g + 1 + k))
                var dot = 0.0
                var na = 0.0
                var nb = 0.0
                for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
                out[g] = dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb) + 1e-9)
            }
            return out
        }
        val expected = reference(12)
        val times = ArrayList<Long>()
        repeat(2) { TopicSegmenter.gapSims(vectors, 12) }
        repeat(4) {
            val t0 = System.nanoTime()
            TopicSegmenter.gapSims(vectors, 12)
            times += (System.nanoTime() - t0) / 1_000_000
        }
        val large = median(times)
        assertTrue(expected.contentEquals(TopicSegmenter.gapSims(vectors, 12)), "Изменились значения косинусов")
        assertTrue(large < 8, "Параллельная обработка не ускорила k12: ${large}ms, все=$times")
    }
}
