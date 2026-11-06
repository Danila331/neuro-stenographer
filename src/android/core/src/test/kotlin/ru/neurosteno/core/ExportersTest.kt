package ru.neurosteno.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExportersTest {
    private val tr = Transcript(
        listOf(
            Utterance("speaker_1", 1250, 4380, "Добрый день!", emptyList()),
            Utterance("speaker_2", 3_723_004, 3_725_000, "Это <тест> & проверка.", emptyList()),
        ),
        TranscriptMeta("lite", 4_000_000, 1, emptyMap(), emptyMap(), "1"),
    )

    @Test
    fun srt() {
        assertEquals(
            "1\n00:00:01,250 --> 00:00:04,380\nspeaker_1: Добрый день!\n\n" +
                "2\n01:02:03,004 --> 01:02:05,000\nspeaker_2: Это <тест> & проверка.\n\n",
            SrtExporter().export(tr),
        )
    }

    @Test
    fun vtt() {
        assertEquals(
            "WEBVTT\n\n1\n00:00:01.250 --> 00:00:04.380\n<v speaker_1>Добрый день!\n\n" +
                "2\n01:02:03.004 --> 01:02:05.000\n<v speaker_2>Это &lt;тест&gt; &amp; проверка.\n\n",
            VttExporter().export(tr),
        )
    }

    @Test
    fun txt() {
        assertEquals(
            "[00:01] speaker_1: Добрый день!\n[1:02:03] speaker_2: Это <тест> & проверка.\n",
            TxtExporter().export(tr),
        )
    }

    @Test
    fun `custom speaker names`() {
        val names: SpeakerNames = { if (it == "speaker_1") "Анна" else it }
        assertEquals(true, TxtExporter(names).export(tr).startsWith("[00:01] Анна: "))
    }

    @Test
    fun `json exporters`() {
        assertEquals(TranscriptJson.toJson(tr), JsonExporter.export(tr))
        assertEquals("full.json", FullJsonExporter.fileExtension)
    }

    @Test
    fun `empty transcript`() {
        val empty = Transcript(emptyList(), tr.meta)
        assertEquals("", SrtExporter().export(empty))
        assertEquals("WEBVTT\n\n", VttExporter().export(empty))
        assertEquals("", TxtExporter().export(empty))
    }
}
