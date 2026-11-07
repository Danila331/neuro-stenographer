package ru.neurosteno.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptJsonTest {
    private fun u(spk: String, s: Long, e: Long, text: String) = Utterance(spk, s, e, text, emptyList())

    @Test
    fun `exact spec example`() {
        val json = TranscriptJson.toJson(listOf(u("speaker_1", 1250, 4380, "Добрый день!")), 10_000)
        assertEquals("""{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}""", json)
    }

    @Test
    fun `cyrillic is not escaped and output is valid utf8`() {
        val json = TranscriptJson.toJson(listOf(u("a", 0, 10, "Привет, ёжик 🐱")), 100)
        assertFalse(json.contains("\\u"))
        assertTrue(json.contains("Привет, ёжик 🐱"))
        val bytes = json.toByteArray(Charsets.UTF_8)
        assertEquals(json, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `empty transcript`() {
        assertEquals("""{"transcript":[]}""", TranscriptJson.toJson(emptyList(), 1000))
        assertEquals("""{"transcript":[]}""", TranscriptJson.toJson(listOf(u("a", 0, 10, "x")), 0))
    }

    @Test
    fun `speakers renumbered by first appearance after sorting`() {
        val src = listOf(u("zzz", 5000, 6000, "второй"), u("kk", 0, 1000, "первый"), u("zzz", 1500, 2000, "третий"))
        val r = TranscriptJson.parse(TranscriptJson.toJson(src, 10_000))
        assertEquals(listOf("первый", "третий", "второй"), r.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_2"), r.map { it.speakerId })
    }

    @Test
    fun `sorted by start then end`() {
        val src = listOf(u("a", 100, 900, "b"), u("a", 100, 500, "a"), u("a", 0, 50, "z"))
        assertEquals(listOf("z", "a", "b"), TranscriptJson.parse(TranscriptJson.toJson(src, 1000)).map { it.text })
    }

    @Test
    fun `invariants clamp times drop empty and trim`() {
        val src = listOf(
            u("a", -50, 0, "  отрицательное начало \n"),
            u("a", 900, 5000, "за пределами"),
            u("a", 2000, 2000, "нулевая длина"),
            u("a", 3000, 3100, "   "),
            u("a", 5000, 6000, "после конца"),
        )
        val r = TranscriptJson.parse(TranscriptJson.toJson(src, 1000))
        r.forEach {
            assertTrue(it.startMs in 0 until it.endMs, "start<end: $it")
            assertTrue(it.endMs <= 1000, "end<=duration: $it")
            assertEquals(it.text.trim(), it.text)
            assertTrue(it.text.isNotEmpty())
        }
        assertEquals("отрицательное начало", r[0].text)
        assertEquals(4, r.size)
    }

    @Test
    fun `quotes backslashes and control chars are escaped and round trip`() {
        val tricky = "он сказал \"привет\" \\ путь\tтаб\nстрока\u0001"
        val json = TranscriptJson.toJson(listOf(u("a", 0, 10, tricky)), 100)
        val parsed = Json.parseToJsonElement(json).jsonObject["transcript"]!!.jsonArray[0].jsonObject["text"] as JsonPrimitive
        assertEquals("он сказал \"привет\" \\ путь таб строка", parsed.content)
    }

    @Test
    fun `lone surrogate is replaced`() {
        val json = TranscriptJson.toJson(listOf(u("a", 0, 10, "ab\uD800cd")), 100)
        assertTrue(json.contains("ab�cd"))
    }

    @Test
    fun `keys order and set are exactly as in spec and numbers are integers`() {
        val json = TranscriptJson.toJson(listOf(u("a", 1, 2, "x")), 100)
        assertTrue(json.startsWith("""{"transcript":[{"speaker_id":"""))
        assertTrue(Regex(""""start_ms":\d+,"end_ms":\d+,"text":""").containsMatchIn(json))
        assertFalse(json.contains("."))
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("transcript"), o.keys)
        assertEquals(listOf("speaker_id", "start_ms", "end_ms", "text"), o["transcript"]!!.jsonArray[0].jsonObject.keys.toList())
    }

    @Test
    fun `normalize is idempotent`() {
        val src = listOf(u("b", 10, 20, "x"), u("a", 0, 5, " y "))
        val once = TranscriptJson.normalize(src, 100)
        assertEquals(once, TranscriptJson.normalize(once, 100))
    }

    @Test
    fun `full json has words meta and rtf`() {
        val meta = TranscriptMeta("lite", 10_000, 5_000, mapOf("asr" to 3000L, "total" to 5000L), mapOf("asr" to "gigaam"), "1.0")
        val tr = Transcript(
            listOf(Utterance("speaker_1", 0, 500, "Привет.", listOf(Word("привет", 0, 500, 0.9f)))),
            meta,
        )
        val o = Json.parseToJsonElement(TranscriptJson.toFullJson(tr)).jsonObject
        val utt = o["transcript"]!!.jsonArray[0].jsonObject
        assertEquals("Привет.", (utt["text"] as JsonPrimitive).content)
        assertEquals(1, utt["words"]!!.jsonArray.size)
        val m = o["meta"] as JsonObject
        assertEquals("lite", (m["tier"] as JsonPrimitive).content)
        assertEquals(0.5, (m["rtf"] as JsonPrimitive).content.toDouble(), 1e-9)
        assertFalse(TranscriptJson.toFullJson(tr).contains("\\u04"))
    }
}
