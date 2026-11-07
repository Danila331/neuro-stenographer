package ru.neurosteno.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.neurosteno.app.ui.screens.FragmentLanguage
import ru.neurosteno.app.ui.screens.FragmentOptions

class FragmentOptionsTest {
    @Test
    fun languageMapping() {
        assertEquals("en", FragmentLanguage.AS_RECORDED.request("en"))
        assertEquals("ru", FragmentLanguage.AS_RECORDED.request(""))
        assertEquals("auto", FragmentLanguage.AUTO.request("en"))
        assertEquals("ru", FragmentLanguage.RU.request("en"))
        assertEquals("en", FragmentLanguage.EN.request("ru"))
    }

    @Test
    fun defaultsReuseSpeakersFromOldUtterances() {
        val p = FragmentOptions(FragmentLanguage.AS_RECORDED, recomputeSpeakers = false, numSpeakers = 3).toParams("e", 1000, 5000, "ru", 2)
        assertEquals(false, p.recomputeSpeakers)
        assertNull(p.numSpeakers) // без пересчёта говорящих число игнорируется
        assertEquals("ru", p.language)
        assertEquals(1000, p.startMs)
        assertEquals(5000, p.endMs)
    }

    @Test
    fun recomputeUsesChosenSpeakerCountForSeveralUtterances() {
        assertEquals(3, FragmentOptions(FragmentLanguage.AUTO, true, 3).toParams("e", 0, 1, "ru", 4).numSpeakers)
        assertNull(FragmentOptions(FragmentLanguage.AUTO, true, null).toParams("e", 0, 1, "ru", 4).numSpeakers)
    }

    @Test
    fun singleUtteranceMeansSingleSpeaker() {
        assertEquals(1, FragmentOptions(FragmentLanguage.AUTO, true, 4).toParams("e", 0, 1, "ru", 1).numSpeakers)
    }
}
