package ru.neurosteno.store

import java.nio.file.Files
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Тест на хосте с нативной библиотекой ObjectBox для macOS; без неё (другая ОС) пропускается. */
class ObjectBoxVectorStoreTest {
    private fun unit(rnd: java.util.Random): FloatArray {
        val v = FloatArray(384) { rnd.nextGaussian().toFloat() }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(384) { v[it] / n }
    }

    private fun open(dir: java.io.File): ObjectBoxVectorStore? =
        runCatching { ObjectBoxVectorStore.open(dir) }.onFailure { println("ObjectBox недоступен на хосте: $it") }.getOrNull()

    @Test
    fun upsertSearchDeleteAndPersistence() {
        val dir = Files.createTempDirectory("ob").toFile()
        try {
            val s = open(dir)
            assumeTrue(s != null)
            s!!
            val rnd = java.util.Random(3)
            val vecs = List(600) { unit(rnd) }
            s.upsert("a", (1L..300L).toList(), vecs.take(300))
            s.upsert("b", (301L..600L).toList(), vecs.drop(300))
            assertEquals(600, s.count())
            assertEquals(setOf("a", "b"), s.recordIds())
            // запрос = вектор 450 -> он же первый (косинус ~1), и по всей истории (HNSW), и внутри записи b (перебор)
            val all = s.search(vecs[449], 5)
            assertEquals(450L, all.first().first)
            assertTrue(all.first().second > 0.999f)
            assertEquals(450L, s.search(vecs[449], 5, "b").first().first)
            assertTrue(s.search(vecs[449], 5, "a").all { it.first <= 300L })
            // замена записи удаляет старые векторы
            s.upsert("a", listOf(1000L), listOf(vecs[0]))
            assertEquals(301, s.count())
            s.deleteRecord("b")
            assertEquals(setOf("a"), s.recordIds())
            assertEquals(1000L, s.search(vecs[0], 3).first().first)
            s.close()
            val s2 = open(dir)!!
            assertEquals(1, s2.count())
            s2.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Кластеризованные векторы (как у настоящих эмбеддингов реплик; ср. `bench_rag.vecdb_bench.make_data`): n/100 центров + шум. */
    private fun clustered(n: Int, rnd: java.util.Random): List<FloatArray> {
        val centers = List(maxOf(10, n / 100)) { unit(rnd) }
        return List(n) {
            val c = centers[rnd.nextInt(centers.size)]
            val v = FloatArray(384) { j -> c[j] + 0.7f * rnd.nextGaussian().toFloat() / sqrt(384f) }
            val nrm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(384) { v[it] / nrm }
        }
    }

    @Test
    fun hnswRecallAgainstExactOnClusteredVectors() {
        val dir = Files.createTempDirectory("ob").toFile()
        try {
            val s = open(dir)
            assumeTrue(s != null)
            s!!
            val rnd = java.util.Random(11)
            val n = 5000
            val vecs = clustered(n, rnd)
            s.upsert("all", (1L..n).toList(), vecs)
            var hit = 0
            val trials = 40
            repeat(trials) {
                val base = vecs[rnd.nextInt(n)]
                val q = FloatArray(384) { base[it] + 0.3f * rnd.nextGaussian().toFloat() / sqrt(384f) }.let { v -> val nr = sqrt(v.sumOf { x -> (x * x).toDouble() }).toFloat(); FloatArray(384) { v[it] / nr } }
                val exact = (0 until n).sortedByDescending { i -> var d = 0f; for (j in 0 until 384) d += vecs[i][j] * q[j]; d }.take(10).map { it + 1L }.toSet()
                hit += s.search(q, 10).map { it.first }.toSet().intersect(exact).size
            }
            println("HNSW recall@10 (кластеризованные векторы, n=$n): ${hit / (trials * 10.0)}")
            assertTrue("recall@10 = ${hit / (trials * 10.0)}", hit >= trials * 10 * 0.8)
            s.close()
        } finally {
            dir.deleteRecursively()
        }
    }

}
