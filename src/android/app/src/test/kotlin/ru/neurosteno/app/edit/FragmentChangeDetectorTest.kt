package ru.neurosteno.app.edit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.core.Utterance

class FragmentChangeDetectorTest {
    private fun utt(speaker: String = "speaker_1", start: Long = 100, end: Long = 900, text: String = "Добрый день!") =
        Utterance(speaker, start, end, text, emptyList())

    @Test
    fun acceptsSameTextAndSpeakerWithBoundaryDriftUpToFiftyMs() {
        assertTrue(FragmentChangeDetector.isUnchanged(
            listOf(utt()), listOf(utt(start = 150, end = 850)),
        ))
    }

    @Test
    fun rejectsChangedTextSpeakerCountOrBoundaries() {
        assertFalse(FragmentChangeDetector.isUnchanged(listOf(utt()), listOf(utt(start = 151))))
        assertFalse(FragmentChangeDetector.isUnchanged(listOf(utt()), listOf(utt(text = "Доброе утро!"))))
        assertFalse(FragmentChangeDetector.isUnchanged(listOf(utt()), listOf(utt(speaker = "speaker_2"))))
        assertFalse(FragmentChangeDetector.isUnchanged(listOf(utt()), emptyList()))
    }

    @Test
    fun comparesSpeakerIdsAfterCanonicalRemap() {
        assertTrue(FragmentChangeDetector.isUnchanged(
            listOf(utt(speaker = "speaker_2")),
            listOf(utt(speaker = "speaker_1")),
            speakerRemap = mapOf("speaker_2" to "speaker_1"),
        ))
    }
}
