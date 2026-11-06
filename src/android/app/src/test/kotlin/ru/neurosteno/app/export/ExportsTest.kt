package ru.neurosteno.app.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.app.TestData
import ru.neurosteno.app.edit.EditableTranscript

class ExportsTest {
    private val source = TestData.dialog()
    private val named = EditableTranscript.of(source, mapOf("speaker_2" to "Мурзик", "speaker_1" to "Барсик"))

    @Test
    fun jsonIsStrictlyTzFormatAndIgnoresNames() {
        val json = Exports.render(ExportFormat.JSON, named, source)
        assertEquals(
            """{"transcript":[{"speaker_id":"speaker_1","start_ms":1000,"end_ms":3000,"text":"добрый день"},""" +
                """{"speaker_id":"speaker_2","start_ms":3500,"end_ms":6000,"text":"здравствуйте коллеги"},""" +
                """{"speaker_id":"speaker_1","start_ms":6500,"end_ms":9000,"text":"начнём совещание"}]}""",
            json,
        )
        assertFalse(json.contains("Мурзик"))
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("transcript"), root.keys)
        (root["transcript"] as JsonArray).forEach {
            assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), (it as JsonObject).keys)
        }
    }

    @Test
    fun editedJsonStaysValidAfterEdits() {
        val edited = ru.neurosteno.app.edit.TranscriptEditor.mergeWithNext(named, 0)
        val items = ru.neurosteno.core.TranscriptJson.parse(Exports.render(ExportFormat.JSON, edited, source))
        assertEquals(2, items.size)
        assertEquals("добрый день здравствуйте коллеги", items[0].text)
        assertTrue(items.all { it.startMs < it.endMs })
    }

    @Test
    fun txtSrtVttUseSpeakerNames() {
        val txt = Exports.render(ExportFormat.TXT, named, source)
        assertTrue(txt, txt.startsWith("[00:01] Мурзик: добрый день\n[00:03] Барсик: здравствуйте коллеги"))
        val srt = Exports.render(ExportFormat.SRT, named, source)
        assertTrue(srt, srt.startsWith("1\n00:00:01,000 --> 00:00:03,000\nМурзик: добрый день"))
        val vtt = Exports.render(ExportFormat.VTT, named, source)
        assertTrue(vtt, vtt.startsWith("WEBVTT") && vtt.contains("<v Мурзик>добрый день"))
    }

    @Test
    fun txtWithoutNamesUsesRussianDefaults() {
        val txt = Exports.render(ExportFormat.TXT, EditableTranscript.of(source), source)
        assertTrue(txt, txt.contains("Говорящий 1: добрый день"))
    }

    @Test
    fun fullJsonHasSpeakersAndMeta() {
        val full = Json.parseToJsonElement(Exports.render(ExportFormat.FULL_JSON, named, source)).jsonObject
        assertEquals("Мурзик", full["speakers"]!!.jsonObject["speaker_1"]!!.jsonPrimitive.content)
        assertEquals("medium", full["meta"]!!.jsonObject["tier"]!!.jsonPrimitive.content)
        assertEquals(3, (full["transcript"] as JsonArray).size)
    }

    @Test
    fun fileNames() {
        assertEquals("meeting.json", Exports.fileName("meeting.m4a", ExportFormat.JSON))
        assertEquals("a_b.full.json", Exports.fileName("a/b", ExportFormat.FULL_JSON))
        assertEquals("transcript.txt", Exports.fileName("  ", ExportFormat.TXT))
    }
}
