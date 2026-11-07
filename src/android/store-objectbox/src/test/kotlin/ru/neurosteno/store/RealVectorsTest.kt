package ru.neurosteno.store

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * ObjectBox HNSW на НАСТОЯЩИХ векторах корпуса cats (USER2-small, 384-мерные окна win3s1 и векторы вопросов из `gen_search_eval.py`):
 * совпадение top-5 с точным перебором + масштабирование (кластеризованные копии окон до 20 000) -- задержка запроса, размер базы, RSS.
 */
class RealVectorsTest {
    private fun floats(f: File): List<FloatArray> {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return List(bb.remaining() / 384) { FloatArray(384).also { v -> bb.get(v) } }
    }

    private fun rssMb(): Long = runCatching {
        ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start().inputStream.bufferedReader().readText().trim().toLong() / 1024
    }.getOrDefault(-1)

    @Test
    fun hnswOnRealEmbeddings() {
        val root = System.getenv("NEUROSTENO_ROOT")?.let { File(it, ".cache/search-eval") }
        assumeTrue(root != null && File(root, "windows-user2-small.f32").isFile)
        val wins = floats(File(root!!, "windows-user2-small.f32"))
        val qs = floats(File(root, "questions-user2-small.f32"))
        val dir = Files.createTempDirectory("ob").toFile()
        try {
            val s = runCatching { ObjectBoxVectorStore.open(dir) }.getOrNull()
            assumeTrue(s != null)
            s!!
            s.upsert("all", wins.indices.map { it + 1L }, wins)
            var inter = 0
            for (q in qs) {
                val exact = wins.indices.sortedByDescending { i -> var d = 0f; for (j in 0 until 384) d += wins[i][j] * q[j]; d }.take(5).map { it + 1L }.toSet()
                inter += s.search(q, 5).map { it.first }.toSet().intersect(exact).size
            }
            val recall = inter / (qs.size * 5.0)
            println("ObjectBox HNSW на реальных векторах (${wins.size} окон, ${qs.size} вопросов): recall@5 против точного перебора = $recall")

            // масштаб: n копий окон с шумом (кластеры сохраняются), замеры задержки одного запроса
            val rnd = java.util.Random(5)
            val n = 20_000
            val big = List(n) { val b = wins[rnd.nextInt(wins.size)]; val v = FloatArray(384) { j -> b[j] + 0.02f * rnd.nextGaussian().toFloat() }; val nr = sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(384) { v[it] / nr } }
            val t0 = System.nanoTime()
            s.upsert("big", big.indices.map { 100_000L + it }, big)
            val buildS = (System.nanoTime() - t0) / 1e9
            val lat = qs.map { q -> val t = System.nanoTime(); s.search(q, 50); (System.nanoTime() - t) / 1e6 }.sorted()
            val sizeMb = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576.0
            println("ObjectBox $n окон: построение %.1f с, запрос k=50 p50=%.2f мс p95=%.2f мс, база %.1f МБ, RSS %d МБ".format(buildS, lat[lat.size / 2], lat[lat.size * 95 / 100], sizeMb, rssMb()))
            assertTrue("recall@5 (малая история, точный перебор) = $recall", recall >= 0.999)
            // большая история идёт через HNSW + точное уточнение: совпадение top-5 с перебором
            var inter2 = 0
            val all = wins + big
            val allIds = wins.indices.map { it + 1L } + big.indices.map { 100_000L + it }
            for (q in qs) {
                val exact = all.indices.sortedByDescending { i -> var d = 0f; for (j in 0 until 384) d += all[i][j] * q[j]; d }.take(5).map { allIds[it] }.toSet()
                inter2 += s.search(q, 5).map { it.first }.toSet().intersect(exact).size
            }
            println("ObjectBox HNSW + уточнение, ${all.size} окон: recall@5 = ${inter2 / (qs.size * 5.0)}")
            assertTrue("recall@5 HNSW = ${inter2 / (qs.size * 5.0)}", inter2 / (qs.size * 5.0) >= 0.85)
            s.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
