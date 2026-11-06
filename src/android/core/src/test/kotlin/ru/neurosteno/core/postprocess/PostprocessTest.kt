package ru.neurosteno.core.postprocess

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.neurosteno.core.Word

class PostprocessTest {
    private fun norm(s: String) = s.replace('ё', 'е').trim().split(Regex("\\s+")).joinToString(" ")

    @Test
    fun `spell numbers matches stenoeval num2words on the reference table`() {
        val json = javaClass.getResourceAsStream("/num_cases.json")!!.bufferedReader(Charsets.UTF_8).readText()
        val cases = Json.parseToJsonElement(json).jsonObject
        var checked = 0
        for ((src, exp) in cases) {
            val want = norm((exp as JsonPrimitive).content)
            val got = norm(RussianNumbers.spell(src))
            assertEquals(want, got, "для '$src'")
            checked++
        }
        assertTrue(checked > 50)
    }

    @Test
    fun `tiny chunk detector counts letters and digits only`() {
        assertTrue(Postprocess.isTinyChunk("а", 2))
        assertTrue(Postprocess.isTinyChunk("Э.", 2))
        assertTrue(Postprocess.isTinyChunk("  ", 2))
        assertTrue(Postprocess.isTinyChunk("а-а!", 2))
        assertFalse(Postprocess.isTinyChunk("няу", 2))
        assertFalse(Postprocess.isTinyChunk("да нет", 2))
    }

    @Test
    fun `number words are expanded with split timing and punctuation stays on the last piece`() {
        val r = Postprocess.spellNumbers(listOf(Word("в", 0, 100), Word("25,", 100, 900), Word("мышей", 900, 1200)))
        assertEquals(listOf("в", "двадцать", "пять,", "мышей"), r.map { it.text })
        assertEquals(100L, r[1].startMs); assertEquals(500L, r[1].endMs); assertEquals(500L, r[2].startMs); assertEquals(900L, r[2].endMs)
    }

    @Test
    fun `words without digits and single word numbers are untouched or replaced in place`() {
        val src = listOf(Word("привет", 0, 10), Word("7", 10, 20))
        val r = Postprocess.spellNumbers(src)
        assertEquals(listOf("привет", "семь"), r.map { it.text })
        assertEquals(src[0], r[0])
        assertEquals(10L, r[1].startMs); assertEquals(20L, r[1].endMs)
    }
}
