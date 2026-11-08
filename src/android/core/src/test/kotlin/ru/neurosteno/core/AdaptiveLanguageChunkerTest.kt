package ru.neurosteno.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdaptiveLanguageChunkerTest {
    private fun detect(c: TimeSpan): String? = if (c.endMs - c.startMs < 1500) null else if (c.startMs < 6000) "en" else "ru"

    @Test
    fun `mixed chunk splits only at language change`() {
        val (chunks, raw) = AdaptiveLanguageChunker.split(listOf(TimeSpan(0, 12000)), ::detect)
        assertEquals(listOf(TimeSpan(0, 5000), TimeSpan(5000, 10000), TimeSpan(10000, 12000)), chunks)
        assertEquals(listOf("en", "en", "ru"), raw)
    }

    @Test
    fun `single language chunk preserves original boundaries`() {
        val original = TimeSpan(0, 12000)
        val (chunks, raw) = AdaptiveLanguageChunker.split(listOf(original), detect = { _ -> "ru" })
        assertEquals(listOf(original), chunks)
        assertEquals(listOf("ru"), raw)
    }

    @Test
    fun `short english tail does not force split`() {
        val original = TimeSpan(0, 5300)
        val (chunks, raw) = AdaptiveLanguageChunker.split(listOf(original), ::detect)
        assertEquals(listOf(original), chunks)
        assertEquals(listOf("en"), raw)
    }

    @Test
    fun `vad subchunks confirm english without splitting single language asr`() {
        val original = TimeSpan(0, 5_320)
        val fine = listOf(TimeSpan(0, 3_035), TimeSpan(3_035, 5_320))
        val (chunks, raw, languages) = AdaptiveLanguageChunker.plan(listOf(original), fine, detect = { _ -> "en" })
        assertEquals(listOf(original), chunks)
        assertEquals(listOf("en"), raw)
        assertEquals(listOf("en"), languages)
    }

    @Test
    fun `vad subchunks split mixed asr at speech boundaries`() {
        val original = TimeSpan(0, 12_875)
        val fine = listOf(TimeSpan(0, 3_035), TimeSpan(3_035, 5_514), TimeSpan(6_226, 9_861), TimeSpan(9_861, 12_875))
        val (chunks, raw, languages) = AdaptiveLanguageChunker.plan(listOf(original), fine, ::detect)
        assertEquals(fine, chunks)
        assertEquals(listOf("en", "en", "ru", "ru"), raw)
        assertEquals(listOf("en", "en", "ru", "ru"), languages)
    }

    @Test
    fun `empty input is safe`() {
        assertEquals(Pair(emptyList<TimeSpan>(), emptyList<String?>()), AdaptiveLanguageChunker.split(emptyList(), ::detect))
    }
}
