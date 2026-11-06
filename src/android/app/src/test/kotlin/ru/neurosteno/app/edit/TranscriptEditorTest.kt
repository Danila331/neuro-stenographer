package ru.neurosteno.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.app.TestData
import ru.neurosteno.app.TestData.utt
import ru.neurosteno.core.Transcript

class TranscriptEditorTest {
    private fun base(names: Map<String, String> = emptyMap()) = EditableTranscript.of(TestData.dialog(), names)

    @Test
    fun speakersAreRenumberedByFirstAppearanceAndNamesFollow() {
        // в сырых данных первым говорит speaker_2 -- после канонизации он становится speaker_1, имя переезжает вместе с ним
        val t = EditableTranscript.of(TestData.dialog(), mapOf("speaker_2" to "Мурзик", "speaker_1" to "Барсик"))
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), t.utterances.map { it.speakerId })
        assertEquals("Мурзик", t.displayName("speaker_1"))
        assertEquals("Барсик", t.displayName("speaker_2"))
    }

    @Test
    fun defaultNameIsRussianLabel() {
        assertEquals("Говорящий 3", EditableTranscript.defaultName("speaker_3"))
        assertEquals("Говорящий 1", base().displayName("speaker_1"))
    }

    @Test
    fun renameAndResetToDefault() {
        var t = TranscriptEditor.rename(base(), "speaker_1", "  Мурзик  ")
        assertEquals("Мурзик", t.displayName("speaker_1"))
        t = TranscriptEditor.rename(t, "speaker_1", "")
        assertEquals("Говорящий 1", t.displayName("speaker_1"))
        assertTrue(t.speakerNames.isEmpty())
    }

    @Test
    fun setTextDropsStaleWordsAndKeepsOthers() {
        val t = TranscriptEditor.setText(base(), 1, "Здравствуйте, коллеги!")
        assertEquals("Здравствуйте, коллеги!", t.utterances[1].text)
        assertTrue(t.utterances[1].words.isEmpty())
        assertEquals(2, t.utterances[0].words.size)
    }

    @Test
    fun setTextNoChangeReturnsSameInstance() {
        val t = base()
        assertTrue(TranscriptEditor.setText(t, 0, t.utterances[0].text) === t)
    }

    @Test
    fun mergeWithNextCombinesTextTimeAndWords() {
        val t = TranscriptEditor.mergeWithNext(base(), 0)
        assertEquals(2, t.utterances.size)
        val m = t.utterances[0]
        assertEquals("добрый день здравствуйте коллеги", m.text)
        assertEquals(1_000, m.startMs)
        assertEquals(6_000, m.endMs)
        assertEquals("speaker_1", m.speakerId)
        assertEquals(4, m.words.size)
    }

    @Test
    fun mergeLastIsNoop() {
        val t = base()
        assertTrue(TranscriptEditor.mergeWithNext(t, t.utterances.lastIndex) === t)
    }

    @Test
    fun changingSpeakerOfFirstUtteranceRenumbersAndKeepsNames() {
        // Мурзик = speaker_1 (реплики 0 и 2), Барсик = speaker_2. Отдаём реплику 0 Барсику: Барсик теперь первый -> speaker_1
        val named = TranscriptEditor.rename(TranscriptEditor.rename(base(), "speaker_1", "Мурзик"), "speaker_2", "Барсик")
        val t = TranscriptEditor.setSpeaker(named, 0, "speaker_2")
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2"), t.utterances.map { it.speakerId })
        assertEquals("Барсик", t.displayName("speaker_1"))
        assertEquals("Мурзик", t.displayName("speaker_2"))
    }

    @Test
    fun newSpeakerGetsFreeId() {
        val t = base()
        val id = TranscriptEditor.newSpeakerId(t)
        assertEquals("speaker_3", id)
        val changed = TranscriptEditor.setSpeaker(t, 0, id)
        assertEquals(3, changed.speakerIds.size)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3"), changed.utterances.map { it.speakerId })
    }

    @Test
    fun emptyTextRemovesUtteranceAfterCanonicalization() {
        val t = TranscriptEditor.setText(base(), 1, "   ")
        assertEquals(2, t.utterances.size)
    }

    @Test
    fun deleteRemovesUtterance() {
        val t = TranscriptEditor.delete(base(), 1)
        assertEquals(listOf("добрый день", "начнём совещание"), t.utterances.map { it.text })
        assertEquals(1, t.speakerIds.size)
    }

    @Test
    fun canonicalIsIdempotent() {
        val once = base(mapOf("speaker_1" to "А"))
        assertEquals(once, TranscriptEditor.canonical(once))
    }

    @Test
    fun indexAtFindsCurrentUtterance() {
        val u = base().utterances
        assertEquals(-1, TranscriptEditor.indexAt(u, 500))
        assertEquals(0, TranscriptEditor.indexAt(u, 1_000))
        assertEquals(0, TranscriptEditor.indexAt(u, 2_999))
        assertEquals(-1, TranscriptEditor.indexAt(u, 3_200)) // пауза между репликами
        assertEquals(1, TranscriptEditor.indexAt(u, 3_500))
        assertEquals(2, TranscriptEditor.indexAt(u, 8_999))
        assertEquals(-1, TranscriptEditor.indexAt(u, 15_000))
    }

    @Test
    fun indexAtHandlesOverlap() {
        val tr = Transcript(listOf(utt(1, 0, 10_000, "длинная реплика"), utt(2, 2_000, 3_000, "перебил")), TestData.meta)
        val u = EditableTranscript.of(tr).utterances
        assertEquals(1, TranscriptEditor.indexAt(u, 2_500))
        assertEquals(0, TranscriptEditor.indexAt(u, 5_000)) // вторая закончилась, первая ещё идёт
    }
}
