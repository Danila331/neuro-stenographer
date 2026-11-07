package ru.neurosteno.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ru.neurosteno.core.asr.TokenWords
import ru.neurosteno.core.asr.VadChunking
import ru.neurosteno.core.audio.AudioNormalizer
import ru.neurosteno.core.postprocess.Postprocess

/**
 * Паритет Kotlin <-> refpipe на кейсах из `src/test/resources/engine_cases.json`, сгенерированных ИЗ ФУНКЦИЙ ЭТАЛОНА
 * (`scripts/gen_engine_cases.py`): rescue_segments, pad_segments/pack_chunks, strip_dashes_text, build_words, normalize_gain,
 * smooth_languages. Сигналы заданы формулой (огибающая + целочисленный LCG-шум) и воспроизводятся бит в бит.
 */
class EngineCasesTest {
    private val cases: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/engine_cases.json")!!.bufferedReader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }
    private fun JsonElement.pairs() = jsonArray.map { it.jsonArray[0].jsonPrimitive.int to it.jsonArray[1].jsonPrimitive.int }

    private fun signal(total: Int, env: JsonArray): FloatArray {
        val amp = DoubleArray(total) { 0.0005 }
        for (e in env) {
            val a = e.jsonArray[0].jsonPrimitive.int
            val b = e.jsonArray[1].jsonPrimitive.int
            val v = e.jsonArray[2].jsonPrimitive.double
            for (i in a until minOf(b, total)) amp[i] = v
        }
        return FloatArray(total) { i ->
            val s = (i.toLong() * 1103515245L + 12345L) and 0x7FFFFFFFL
            val noise = ((s shr 8) and 0xFFFFL).toDouble() / 32768.0 - 1.0
            (amp[i] * noise).toFloat()
        }
    }

    private fun num(e: JsonElement?, def: Double) = (e as? JsonPrimitive)?.doubleOrNull ?: def

    @TestFactory
    fun `rescue_segments matches refpipe`(): List<DynamicTest> = cases["rescue"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("rescue[$i]") {
            val x = signal(o["total"]!!.jsonPrimitive.int, o["env"]!!.jsonArray)
            val cfg = o["cfg"]!!.jsonObject
            val rc = VadRescueConfig(
                enabled = true,
                snrDb = cfg["snr_db"]!!.jsonPrimitive.double,
                absFloorDb = cfg["abs_floor_db"]!!.jsonPrimitive.double,
                bridgeMs = cfg["bridge_ms"]!!.jsonPrimitive.int,
                minMs = cfg["min_ms"]!!.jsonPrimitive.int,
                marginMs = cfg["margin_ms"]!!.jsonPrimitive.int,
            )
            assertEquals(o["expected"]!!.pairs(), VadChunking.rescueSegments(x, o["covered"]!!.pairs(), rc))
        }
    }

    @TestFactory
    fun `pad_segments and pack_chunks match refpipe`(): List<DynamicTest> = cases["pack"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("pack[$i]") {
            val total = o["total"]!!.jsonPrimitive.int
            val x = signal(total, o["env"]!!.jsonArray)
            var segs = o["raw"]!!.pairs()
            val rcJson = o["rescue"]
            if (rcJson != null && rcJson !is JsonNull) {
                val r = rcJson.jsonObject
                val rc = VadRescueConfig(true, r["snr_db"]!!.jsonPrimitive.double, r["abs_floor_db"]!!.jsonPrimitive.double,
                    r["bridge_ms"]!!.jsonPrimitive.int, r["min_ms"]!!.jsonPrimitive.int, r["margin_ms"]!!.jsonPrimitive.int)
                segs = (segs + VadChunking.rescueSegments(x, segs, rc)).sortedWith(compareBy({ it.first }, { it.second }))
            }
            val padded = VadChunking.padSegments(segs, o["pad_ms"]!!.jsonPrimitive.int, total)
            assertEquals(o["expected_padded"]!!.pairs(), padded)
            val chunks = VadChunking.packChunks(padded, o["max_s"]!!.jsonPrimitive.double, o["min_s"]!!.jsonPrimitive.double, x)
            assertEquals(o["expected_chunks"]!!.pairs(), chunks)
        }
    }

    @TestFactory
    fun `strip_dashes matches refpipe`(): List<DynamicTest> = cases["strip_dashes"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("strip_dashes[$i] ${o["mode"]!!.jsonPrimitive.content}") {
            assertEquals(o["expected"]!!.jsonPrimitive.content, Postprocess.stripDashesText(o["text"]!!.jsonPrimitive.content, o["mode"]!!.jsonPrimitive.content))
        }
    }

    @TestFactory
    fun `build_words matches refpipe`(): List<DynamicTest> = cases["build_words"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("build_words[$i]") {
            val tokens = o["tokens"]!!.jsonArray.map { it.jsonPrimitive.content }
            val ts = o["ts"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
            val dur = (o["durations"] as? JsonArray)?.map { it.jsonPrimitive.double.toFloat() }?.toFloatArray()
            val ch = o["chunk"]!!.ints()
            val w = TokenWords.build(
                tokens, ts, ch[0].toLong(), ch[1].toLong(), dur,
                o["tail"]!!.jsonPrimitive.int.toLong(), o["offset"]!!.jsonPrimitive.int.toLong(), o["keep"]!!.jsonPrimitive.content.toBoolean(),
            )
            val exp = o["expected"]!!.jsonArray.map { listOf(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content.toLong(), it.jsonArray[2].jsonPrimitive.content.toLong()) }
            assertEquals(exp, w.map { listOf(it.text, it.startMs, it.endMs) })
        }
    }

    @TestFactory
    fun `normalize_gain matches refpipe`(): List<DynamicTest> = cases["normalize_gain"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("gain[$i]") {
            val j = o["cfg"]!!.jsonObject
            val cfg = NormalizeGainConfig(
                enabled = j["enabled"]!!.jsonPrimitive.content.toBoolean(),
                mode = j["mode"]!!.jsonPrimitive.content,
                peakDbfs = num(j["peak_dbfs"], -1.0),
                maxGainDb = num(j["max_gain_db"], 40.0),
                skipIfPeakBelowDbfs = num(j["skip_if_peak_below_dbfs"], -85.0),
                applyIfPeakBelowDbfs = (j["apply_if_peak_below_dbfs"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
            )
            assertEquals(o["expected_gain_db"]!!.jsonPrimitive.double, AudioNormalizer.gainDb(o["peak"]!!.jsonPrimitive.double, cfg), 1e-9)
        }
    }

    @TestFactory
    fun `smooth_languages matches refpipe`(): List<DynamicTest> = cases["smooth_languages"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        DynamicTest.dynamicTest("smooth[$i]") {
            val raw = o["raw"]!!.jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            val durs = o["durs"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }
            assertEquals(o["expected"]!!.jsonArray.map { it.jsonPrimitive.content }, LanguageSmoother.smooth(raw, durs))
        }
    }

    @org.junit.jupiter.api.Test
    fun `case file is not empty`() {
        assertTrue(cases["rescue"]!!.jsonArray.size >= 10 && cases["pack"]!!.jsonArray.size >= 10)
    }
}
