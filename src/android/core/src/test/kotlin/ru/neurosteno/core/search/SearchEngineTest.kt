package ru.neurosteno.core.search

import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.neurosteno.core.summary.SUtt

class SearchEngineTest {
    /** Детерминированный «эмбеддер»: мешок основ слов, хэшируется в DIM измерений, L2-нормирован. */
    private class BagEmbedder(override val dim: Int = 32) : TextEmbedder {
        var calls = 0
        override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
            calls++
            return texts.map { t ->
                val v = FloatArray(dim)
                for (term in ru.neurosteno.core.summary.SummaryText.terms(t, true, "snow5")) v[Math.floorMod(term.hashCode(), dim)] += 1f
                val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-9f)
                FloatArray(dim) { v[it] / n }
            }
        }
    }

    private fun utt(i: Int, spk: Int, t: Long, text: String) = SUtt(i, "speaker_$spk", spk, t, t + 2000, text)

    private fun rec(id: String, vararg lines: Pair<Int, String>) =
        SearchRecord(id, "Запись $id", lines.mapIndexed { i, (s, txt) -> utt(i, s, i * 3000L, txt) })

    private val r1 = rec("r1", 1 to "Давайте обсудим бюджет на рыбу.", 2 to "В июле потратили восемь тысяч.", 1 to "А в августе?", 2 to "Одиннадцать тысяч двести.", 1 to "Спасибо, перейдём к мышам.")
    private val r2 = rec("r2", 1 to "Сегодня ловили мышей на складе.", 2 to "Нашли сорок семь мышей.", 1 to "Назначим дежурной Багиру.", 2 to "Согласен, это разумно.")

    private fun engine(emb: TextEmbedder? = BagEmbedder(), vs: VectorStore = BruteForceVectorStore(32)) =
        SearchEngine(SearchConfig(), MemoryChunkStore(), vs, { emb })

    @Test
    fun `index search and delete a record`() {
        val e = engine()
        val rep = e.index(r1)
        assertTrue(rep.embedded)
        assertTrue(rep.chunks >= 3)
        e.index(r2)
        assertTrue(e.isIndexed("r1") && e.hasVectors("r1"))

        val all = e.search("мышей на складе")
        assertTrue(all.usedDense)
        assertEquals("r2", all.hits.first().chunk.recordId)
        val one = e.search("мышей на складе", recordId = "r1")
        assertTrue(one.hits.all { it.chunk.recordId == "r1" })

        e.deleteRecord("r2")
        assertFalse(e.isIndexed("r2"))
        assertFalse(e.hasVectors("r2"))
        assertTrue(e.search("мышей на складе").hits.all { it.chunk.recordId == "r1" })
    }

    @Test
    fun `reindexing replaces old windows and vectors`() {
        val e = engine()
        e.index(r1)
        val before = e.search("бюджет").hits.map { it.chunk.id }
        e.index(r1.copy(utts = r1.utts.take(2)))
        val after = e.search("бюджет").hits.map { it.chunk.id }
        assertTrue(before.intersect(after.toSet()).isEmpty(), "идентификаторы окон не переиспользуются")
        assertEquals(1, e.indexedRecords().size)
        assertEquals(1, (e.search("бюджет").hits.map { it.chunk.recordId }.toSet()).size)
    }

    @Test
    fun `without embedder the record stays searchable by BM25 and vectors are built later`() {
        var emb: TextEmbedder? = null
        val e = SearchEngine(SearchConfig(), MemoryChunkStore(), BruteForceVectorStore(32), { emb })
        val rep = e.index(r2)
        assertFalse(rep.embedded)
        assertEquals(setOf("r2"), e.missingVectors())
        val bm = e.search("мышей")
        assertFalse(bm.usedDense)
        assertTrue(bm.hits.isNotEmpty())
        emb = BagEmbedder()
        assertEquals(1, e.reindexMissing())
        assertTrue(e.missingVectors().isEmpty())
        assertTrue(e.search("мышей").usedDense)
    }

    @Test
    fun `failing embedder does not lose the windows`() {
        val bad = object : TextEmbedder {
            override val dim = 32
            override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> = throw IllegalStateException("oom")
        }
        val e = engine(bad)
        assertFalse(e.index(r1).embedded)
        assertTrue(e.isIndexed("r1"))
        assertTrue(e.search("бюджет").hits.isNotEmpty())
    }

    @Test
    fun `speaker restriction uses names resolved at query time`() {
        var names = emptyMap<String, String>()
        val e = SearchEngine(SearchConfig(), MemoryChunkStore(), BruteForceVectorStore(32), { BagEmbedder() }, { names })
        e.index(r1)
        assertTrue(e.search("что говорил говорящий 2 про тысяч").speaker.numbers == setOf(2))
        names = mapOf("speaker_2" to "Тихон")
        val f = e.search("что говорил Тихон про тысяч")
        assertTrue(f.speaker.active)
        assertTrue(f.hits.first().chunk.speakers.contains("speaker_2"))
    }

    @Test
    fun `retainOnly removes records that are gone from history`() {
        val e = engine()
        e.index(r1)
        e.index(r2)
        e.retainOnly(setOf("r1"))
        assertEquals(setOf("r1"), e.indexedRecords())
    }

    @Test
    fun `file stores survive restart`() {
        val dir = Files.createTempDirectory("search").toFile()
        try {
            val e1 = SearchEngine(SearchConfig(), FileChunkStore(File(dir, "chunks")), BruteForceVectorStore(32, File(dir, "vec")), { BagEmbedder() })
            e1.index(r1)
            e1.index(r2)
            val want = e1.search("мышей на складе").hits.map { it.chunk.id }
            val e2 = SearchEngine(SearchConfig(), FileChunkStore(File(dir, "chunks")), BruteForceVectorStore(32, File(dir, "vec")), { BagEmbedder() })
            assertEquals(setOf("r1", "r2"), e2.indexedRecords())
            assertTrue(e2.missingVectors().isEmpty())
            assertEquals(want, e2.search("мышей на складе").hits.map { it.chunk.id })
            e2.deleteRecord("r1")
            val e3 = SearchEngine(SearchConfig(), FileChunkStore(File(dir, "chunks")), BruteForceVectorStore(32, File(dir, "vec")), { BagEmbedder() })
            assertEquals(setOf("r2"), e3.indexedRecords())
            assertEquals(1, e3.search("мышей").hits.map { it.chunk.recordId }.toSet().size)
            // новые идентификаторы не пересекаются со старыми
            val idsBefore = e3.search("мышей").hits.map { it.chunk.id }.toSet()
            e3.index(r1)
            assertTrue(e3.search("бюджет", recordId = "r1").hits.none { it.chunk.id in idsBefore })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `brute force int8 search matches exact cosine order on random vectors`() {
        val rnd = java.util.Random(7)
        val dim = 64
        fun unit() = FloatArray(dim) { rnd.nextGaussian().toFloat() }.let { v -> val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(dim) { v[it] / n } }
        val store = BruteForceVectorStore(dim)
        val vecs = List(3000) { unit() }
        store.upsert("a", (0L until 1500L).toList(), vecs.take(1500))
        store.upsert("b", (1500L until 3000L).toList(), vecs.drop(1500))
        assertEquals(3000, store.count())
        var agree = 0
        val trials = 40
        repeat(trials) {
            val q = unit()
            val exact = vecs.indices.sortedByDescending { i -> var s = 0f; for (j in 0 until dim) s += vecs[i][j] * q[j]; s }.take(10)
            val got = store.search(q, 10).map { it.first.toInt() }
            agree += exact.intersect(got.toSet()).size
            val inB = store.search(q, 5, "b")
            assertTrue(inB.all { it.first >= 1500 })
            assertEquals(inB.map { it.second }.sortedDescending(), inB.map { it.second })
        }
        assertTrue(agree >= trials * 10 * 0.99, "recall@10 int8+f32-rerank: ${agree / (trials * 10.0)}")
        store.deleteRecord("a")
        assertEquals(1500, store.count())
        assertThrows<IllegalArgumentException> { store.upsert("c", listOf(1L), emptyList()) }
    }

    @Test
    fun `chunk ids and file layout are stable`() {
        val a = Chunker.chunkRecord(r1, "win3s1", 10)
        assertEquals(a.indices.map { 10L + it }, a.map { it.id })
        assertNotEquals("", a.first().label)
    }
}
