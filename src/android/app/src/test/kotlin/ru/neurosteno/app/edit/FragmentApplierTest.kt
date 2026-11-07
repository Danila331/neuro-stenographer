package ru.neurosteno.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.app.TestData
import ru.neurosteno.app.TestData.utt
import ru.neurosteno.app.data.StoredTranscript
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.fragment.FragmentOutcome
import ru.neurosteno.core.fragment.FragmentResult

class FragmentApplierTest {
    private fun result(transcript: Transcript, added: List<Utterance>, removed: List<Utterance>, remap: Map<String, String>) = FragmentResult(
        transcript = transcript, json = "", outcome = FragmentOutcome.REPLACED, message = "ok",
        range = TimeSpan(3_500, 6_000), removed = removed, added = added, speakerRemap = remap, speakerAssignments = emptyList(),
        language = "ru", warnings = emptyList(), stageMs = emptyMap(), processingMs = 1,
    )

    @Test
    fun namesFollowSpeakerRemapAndVanishedSpeakersLoseTheirName() {
        // до: speaker_1 = Мурзик, speaker_2 = Барсик; после пересчёта единственный Барсик (бывший speaker_2) стал speaker_1
        val old = StoredTranscript.of(TestData.dialog().let { EditableTranscript.of(it).let { e -> Transcript(e.utterances, it.meta) } },
            mapOf("speaker_1" to "Мурзик", "speaker_2" to "Барсик"))
        val newUtterances = listOf(utt(1, 3_500, 6_000, "новый текст"))
        val r = result(Transcript(newUtterances, TestData.meta), newUtterances, old.utterances.take(2).map { it.let { u -> utt(1, u.start, u.end, u.text) } },
            remap = mapOf("speaker_2" to "speaker_1"))
        val applied = FragmentApplier.apply(old, r)
        assertEquals(mapOf("speaker_1" to "Барсик"), applied.stored.speakerNames)
        assertTrue(applied.stored.edited)
        assertEquals(listOf("новый текст"), applied.stored.utterances.map { it.text })
        assertEquals(listOf(TimeSpan(3_500, 6_000)), applied.addedSpans)
    }

    @Test
    fun namesAreKeptWhenIdsDoNotChange() {
        val tr = TestData.dialog().let { Transcript(EditableTranscript.of(it).utterances, it.meta) }
        val old = StoredTranscript.of(tr, mapOf("speaker_1" to "Мурзик"))
        val replaced = tr.utterances.toMutableList().also { it[1] = it[1].copy(text = "здравствуйте всем") }
        val r = result(Transcript(replaced, tr.meta), listOf(replaced[1]), listOf(tr.utterances[1]), remap = mapOf("speaker_1" to "speaker_1", "speaker_2" to "speaker_2"))
        val applied = FragmentApplier.apply(old, r)
        assertEquals(mapOf("speaker_1" to "Мурзик"), applied.stored.speakerNames)
        assertEquals("здравствуйте всем", applied.stored.utterances[1].text)
        // имена переносятся и в экспорт: после канонизации говорящий всё ещё «Мурзик»
        val editable = EditableTranscript.of(applied.stored.toTranscript(), applied.stored.speakerNames)
        assertEquals("Мурзик", editable.displayName("speaker_1"))
    }
}
