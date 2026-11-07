package ru.neurosteno.core

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import ru.neurosteno.core.diar.SpeakerRelabeler

/** Паритет `SpeakerRelabeler` с эталоном `bench_diar/spkphrase.py` на кейсах из `diar_cases.json` (scripts/gen_diar_cases.py). */
class SpeakerRelabelerTest {
    private val cases: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/diar_cases.json")!!.bufferedReader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun params(o: JsonObject) = RelabelConfig(
        enabled = true, gapMs = o["gap_ms"]!!.jsonPrimitive.int, maxUnitMs = o["max_unit_ms"]!!.jsonPrimitive.int,
        splitMinSideMs = o["split_min_side_ms"]!!.jsonPrimitive.int, padMs = o["pad_ms"]!!.jsonPrimitive.int,
        embedMaxS = o["embed_max_s"]!!.jsonPrimitive.double, minReliableMs = o["min_reliable_ms"]!!.jsonPrimitive.int,
        kMin = o["k_min"]!!.jsonPrimitive.int, kMax = o["k_max"]!!.jsonPrimitive.int,
        maxClusterUnits = o["max_cluster_units"]!!.jsonPrimitive.int, refine = o["refine"]!!.jsonPrimitive.int,
    )

    private fun matrix(e: JsonElement) = e.jsonArray.map { r -> DoubleArray(r.jsonArray.size) { r.jsonArray[it].jsonPrimitive.double } }.toTypedArray()
    private fun spans(e: JsonElement) = e.jsonArray.map { it.jsonArray[0].jsonPrimitive.long to it.jsonArray[1].jsonPrimitive.long }

    @TestFactory
    fun `units match the reference`(): List<DynamicTest> = cases["units"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("units[$i]") {
            val turns = (o["turns"] as? kotlinx.serialization.json.JsonArray)?.map {
                SpeakerTurn(it.jsonArray[0].jsonPrimitive.int, it.jsonArray[1].jsonPrimitive.long, it.jsonArray[2].jsonPrimitive.long)
            }
            val u = SpeakerRelabeler.makeUnits(spans(o["words"]!!), turns, params(o["params"]!!.jsonObject))
            val exp = o["expected"]!!.jsonArray.map { e -> e.jsonArray.map { it.jsonPrimitive.long } }
            assertEquals(exp, u.map { listOf(it.startMs, it.endMs, it.w0.toLong(), it.w1.toLong()) })
        }
    }

    @TestFactory
    fun `unit embeddings match the reference`(): List<DynamicTest> = cases["embed"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("embed[$i]") {
            val total = o["total"]!!.jsonPrimitive.int
            val audio = FloatArray(total) { k ->
                val s = (k.toLong() * 1103515245L + 12345L) and 0x7FFFFFFFL
                ((((s shr 8) and 0xFFFFL).toDouble() / 32768.0 - 1.0) * 0.3).toFloat()
            }
            val p = params(o["params"]!!.jsonObject)
            val units = SpeakerRelabeler.makeUnits(spans(o["words"]!!), null, p)
            assertEquals(o["units"]!!.jsonArray.size, units.size)
            val embedder = object : SpeakerEmbedder {
                override fun embed(segment: FloatArray): FloatArray {
                    val n = segment.size
                    return floatArrayOf(n.toFloat(), segment[0], segment[n / 2], segment[n - 1], segment[n / 3], 1f)
                }
                override fun close() = Unit
            }
            val e = SpeakerRelabeler.embedUnits(audio, units, embedder, p)
            val exp = matrix(o["expected"]!!)
            assertEquals(exp.size, e.size)
            for (r in exp.indices) for (k in exp[r].indices) assertEquals(exp[r][k], e[r][k], 1e-9, "unit $r dim $k")
        }
    }

    @TestFactory
    fun `ward merges, cuts and silhouettes match the reference`(): List<DynamicTest> = cases["ward"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("ward[$i]") {
            val y = matrix(o["Y"]!!)
            val merges = SpeakerRelabeler.wardMerges(y)
            assertEquals(o["merges"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.int to it.jsonArray[1].jsonPrimitive.int }, merges)
            for ((k, lab) in o["cuts"]!!.jsonObject) {
                val got = SpeakerRelabeler.cut(y.size, merges, k.toInt())
                assertEquals(lab.jsonArray.map { it.jsonPrimitive.int }, got.toList(), "cut k=$k")
                val s = o["silhouettes"]!!.jsonObject[k]!!.jsonPrimitive.double
                assertEquals(s, SpeakerRelabeler.silhouette(y, got), 1e-9, "silhouette k=$k")
            }
        }
    }

    @TestFactory
    fun `unit labels match the reference`(): List<DynamicTest> = cases["label"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("label[$i]") {
            val e = matrix(o["E"]!!)
            val dur = o["dur"]!!.jsonArray.map { it.jsonPrimitive.long }.toLongArray()
            val got = SpeakerRelabeler.labelUnits(e, dur, params(o["params"]!!.jsonObject))
            assertEquals(o["expected"]!!.jsonArray.map { it.jsonPrimitive.int }, got.toList())
        }
    }

    private fun unit(v: DoubleArray): DoubleArray { val n = Math.sqrt(v.sumOf { it * it }); return DoubleArray(v.size) { v[it] / n } }

    @Test
    fun `monologue guard is a threshold on the best silhouette`() {
        val e = Array(12) { i -> unit(if (i % 2 == 0) doubleArrayOf(1.0, 0.05 * (i % 4), 0.0) else doubleArrayOf(0.0, 1.0, 0.05 * (i % 3))) }
        val dur = LongArray(12) { 2000 }
        assertEquals(2, SpeakerRelabeler.labelUnits(e, dur, RelabelConfig(enabled = true, kMax = 2)).toSet().size)
        assertEquals(2, SpeakerRelabeler.labelUnits(e, dur, RelabelConfig(enabled = true, kMax = 2, k1SilhouetteThr = 0.1)).toSet().size)
        assertTrue(SpeakerRelabeler.labelUnits(e, dur, RelabelConfig(enabled = true, kMax = 2, k1SilhouetteThr = 1.01)).all { it == 0 })
    }

    @Test
    fun `phrase turns end to end alternate two voices`() {
        // 12 слов с паузой 1 с -> 12 юнитов; эмбеддер по порядку вызовов чередует два голоса
        val words = (0 until 12).map { Word("w$it", it * 3000L, it * 3000L + 2000) }
        val audio = FloatArray(40 * 16_000) { 0.1f }
        var calls = 0
        val embedder = object : SpeakerEmbedder {
            override fun embed(segment: FloatArray): FloatArray =
                if (calls++ % 2 == 0) floatArrayOf(1f, 0.05f, 0f) else floatArrayOf(0f, 1f, 0.05f)
            override fun close() = Unit
        }
        val turns = SpeakerRelabeler.phraseTurns(words, null, audio, embedder, RelabelConfig(enabled = true, gapMs = 300))
        assertEquals(12, turns.size)
        assertEquals(2, turns.map { it.speaker }.toSet().size)
        assertTrue(turns.indices.all { turns[it].speaker == turns[it % 2].speaker })
        assertEquals(words.map { it.startMs }, turns.map { it.startMs })
    }

    /** Кейсы refpipe (`src/eval/refpipe/tests/relabel_cases.json`, порт Kotlin опирается именно на них). */
    @TestFactory
    fun `refpipe relabel cases`(): List<DynamicTest> {
        val f = java.io.File(TestPaths.repoRoot, "src/eval/refpipe/tests/relabel_cases.json")
        org.junit.jupiter.api.Assumptions.assumeTrue(f.isFile) { "ПРОПУЩЕНО: нет ${f.path}" }
        val cases = Json.parseToJsonElement(f.readText()).jsonObject["cases"]!!.jsonArray
        return cases.map { c ->
            val o = c.jsonObject
            DynamicTest.dynamicTest(o["name"]!!.jsonPrimitive.content) {
                val pj = o["params"]!!.jsonObject
                val p = RelabelConfig(
                    enabled = true, gapMs = pj["gap_ms"]!!.jsonPrimitive.int, maxUnitMs = pj["max_unit_ms"]!!.jsonPrimitive.int,
                    splitMinSideMs = pj["split_min_side_ms"]!!.jsonPrimitive.int, padMs = pj["pad_ms"]!!.jsonPrimitive.int,
                    embedMaxS = pj["embed_max_s"]!!.jsonPrimitive.double, minReliableMs = pj["min_reliable_ms"]!!.jsonPrimitive.int,
                    kMin = pj["k_min"]!!.jsonPrimitive.int, kMax = pj["k_max"]!!.jsonPrimitive.int,
                    maxClusterUnits = pj["max_cluster_units"]!!.jsonPrimitive.int, refine = pj["refine"]!!.jsonPrimitive.int,
                    k1SilhouetteThr = (pj["k1_silhouette_thr"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.double,
                    k1RequireStockSingle = pj["k1_require_stock_single"]!!.jsonPrimitive.content.toBoolean(),
                    k1StockMinShare = pj["k1_stock_min_share"]!!.jsonPrimitive.double,
                )
                val words = spans(o["words"]!!)
                val turns = (o["turns"] as? kotlinx.serialization.json.JsonArray)?.map {
                    SpeakerTurn(it.jsonArray[0].jsonPrimitive.int, it.jsonArray[1].jsonPrimitive.long, it.jsonArray[2].jsonPrimitive.long)
                }
                val exp = o["expected"]!!.jsonObject
                val units = SpeakerRelabeler.makeUnits(words, turns, p)
                assertEquals(exp["units"]!!.jsonArray.map { e -> e.jsonArray.map { it.jsonPrimitive.long } },
                    units.map { listOf(it.startMs, it.endMs, it.w0.toLong(), it.w1.toLong()) })
                val emb = o["embeddings"]
                if (emb == null || emb is JsonNull) return@dynamicTest
                // сырые эмбеддинги -> L2 -> метки (embedUnits не вызываем: звука нет)
                val raw = matrix(emb)
                val e = Array(raw.size) { k ->
                    val r = raw[k]
                    val norm = Math.sqrt(r.sumOf { it * it }) + 1e-9
                    DoubleArray(r.size) { r[it] / norm }
                }
                val dur = LongArray(units.size) { units[it].endMs - units[it].startMs }
                val stockSingle = (o["stock_single"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: false
                val lab = SpeakerRelabeler.labelUnits(e, dur, p, stockSingle)
                assertEquals(exp["labels"]!!.jsonArray.map { it.jsonPrimitive.int }, lab.toList())
                val expTurns = exp["turns"]!!.jsonArray.map { t -> listOf(t.jsonArray[0].jsonPrimitive.long, t.jsonArray[1].jsonPrimitive.long, t.jsonArray[2].jsonPrimitive.long) }
                assertEquals(expTurns, units.mapIndexed { i, u -> listOf(lab[i].toLong(), u.startMs, u.endMs) })
            }
        }
    }

    @Test
    fun `stock_is_single matches the reference unit test`() {
        assertTrue(SpeakerRelabeler.stockIsSingle(listOf(SpeakerTurn(0, 0, 9000), SpeakerTurn(1, 9000, 9500))))
        assertTrue(!SpeakerRelabeler.stockIsSingle(listOf(SpeakerTurn(0, 0, 5000), SpeakerTurn(1, 5000, 9000))))
        assertTrue(SpeakerRelabeler.stockIsSingle(emptyList()))
    }
}
