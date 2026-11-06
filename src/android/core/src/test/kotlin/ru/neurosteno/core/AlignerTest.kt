package ru.neurosteno.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlignerTest {
    private fun w(text: String, s: Long, e: Long) = Word(text, s, e)
    private val aligner = Aligner(AlignConfig(maxGapMs = 1500, minIslandWords = 1, minIslandMs = 400))

    @Test
    fun `empty input gives empty output`() {
        assertTrue(aligner.align(emptyList(), emptyList()).isEmpty())
        assertTrue(aligner.align(listOf(w("  ", 0, 10)), emptyList()).isEmpty())
    }

    @Test
    fun `no turns means single speaker 1`() {
        val r = aligner.align(listOf(w("привет", 0, 500), w("мир", 600, 1000)), emptyList())
        assertEquals(1, r.size)
        assertEquals("speaker_1", r[0].speakerId)
        assertEquals("привет мир", r[0].text)
        assertEquals(0L, r[0].startMs)
        assertEquals(1000L, r[0].endMs)
    }

    @Test
    fun `word takes speaker with max overlap`() {
        val turns = listOf(SpeakerTurn(0, 0, 1000), SpeakerTurn(1, 900, 3000))
        // слово 800..1200: пересечение с turn0 = 200, с turn1 = 300 -> speaker 1
        val r = aligner.align(listOf(w("a", 0, 500), w("b", 800, 1200), w("c", 1300, 2000)), turns)
        assertEquals(listOf("speaker_1", "speaker_2"), r.map { it.speakerId })
        assertEquals("a", r[0].text)
        assertEquals("b c", r[1].text)
    }

    @Test
    fun `overlap tie goes to smaller speaker index`() {
        val turns = listOf(SpeakerTurn(5, 0, 500), SpeakerTurn(2, 500, 1000))
        // "x" 250..750 -- ровно по 250 мс с обоими -> speaker 2
        val r = aligner.align(listOf(w("a", 0, 100), w("x", 250, 750), w("b", 900, 1000)), turns)
        assertEquals(listOf("a", "x b"), r.map { it.text })
    }

    @Test
    fun `word without overlap goes to nearest turn`() {
        val turns = listOf(SpeakerTurn(0, 0, 1000), SpeakerTurn(1, 5000, 6000))
        val r = aligner.align(listOf(w("a", 100, 400), w("b", 1500, 1600), w("c", 4400, 4600)), turns)
        // b ближе к turn0 (расстояние 550), c ближе к turn1 (расстояние 500)
        assertEquals(listOf("a b", "c"), r.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2"), r.map { it.speakerId })
    }

    @Test
    fun `short island between same speaker is reassigned when own overlap is weak`() {
        // "эй" 1000..1300: лучший turn -- speaker 1 (перекрытие 100 из 300 = 33 % < 50 %), длительность 300 < 400
        val turns = listOf(SpeakerTurn(0, 0, 1010), SpeakerTurn(1, 1000, 1100), SpeakerTurn(0, 1290, 3000))
        val r = aligner.align(listOf(w("раз", 0, 900), w("эй", 1000, 1300), w("два", 1400, 2000)), turns)
        assertEquals(1, r.size)
        assertEquals("раз эй два", r[0].text)
    }

    @Test
    fun `short island with overlap at least half of the word is kept`() {
        // перекрытие speaker 1 = 160 из 300 = 53 % -> защищено от сглаживания
        val turns = listOf(SpeakerTurn(0, 0, 1000), SpeakerTurn(1, 1000, 1160), SpeakerTurn(0, 1300, 3000))
        val r = aligner.align(listOf(w("раз", 0, 900), w("да", 1000, 1300), w("два", 1400, 2000)), turns)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), r.map { it.speakerId })
    }

    @Test
    fun `short confident backchannel is protected from smoothing`() {
        // "угу" полностью внутри turn'а speaker 1 (100 % перекрытия) -> остаётся отдельной репликой
        val turns = listOf(SpeakerTurn(0, 0, 1000), SpeakerTurn(1, 1000, 1300), SpeakerTurn(0, 1300, 3000))
        val r = aligner.align(listOf(w("раз", 0, 900), w("угу", 1000, 1300), w("два", 1400, 2000)), turns)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), r.map { it.speakerId })
    }

    @Test
    fun `island longer than min_island_ms is kept`() {
        val turns = listOf(SpeakerTurn(0, 0, 1010), SpeakerTurn(1, 1000, 1700), SpeakerTurn(0, 1700, 3000))
        val r = aligner.align(listOf(w("раз", 0, 900), w("долгое", 1000, 1500), w("два", 1800, 2000)), turns)
        // долгое: 500 мс >= 400 -> остров не сглаживается
        assertEquals(3, r.size)
    }

    @Test
    fun `new utterance on pause greater than max_gap`() {
        val r = aligner.align(listOf(w("a", 0, 100), w("b", 1600, 1700), w("c", 3201, 3300)), emptyList())
        // a->b пауза 1500 (не > 1500) -> склеены; b->c пауза 1501 -> новая реплика
        assertEquals(listOf("a b", "c"), r.map { it.text })
    }

    @Test
    fun `utterance times come from first and last word not from turns`() {
        val r = aligner.align(listOf(w("a", 1250, 1500), w("b", 1600, 4380)), listOf(SpeakerTurn(0, 0, 10_000)))
        assertEquals(1250L, r[0].startMs)
        assertEquals(4380L, r[0].endMs)
    }

    @Test
    fun `speakers are renumbered by first appearance and unused speakers vanish`() {
        val turns = listOf(SpeakerTurn(7, 0, 1000), SpeakerTurn(3, 1000, 2000), SpeakerTurn(9, 5000, 6000))
        val r = aligner.align(listOf(w("a", 100, 900), w("b", 1100, 1900), w("c", 2000, 2500)), turns)
        // c: нет пересечения, ближайший turn3 (расстояние 0.. до 2000) -> speaker 3
        assertEquals(listOf("speaker_1", "speaker_2"), r.map { it.speakerId })
        assertEquals(listOf("a", "b c"), r.map { it.text })
    }

    @Test
    fun `overlapping speech produces overlapping utterances sorted by start`() {
        val turns = listOf(SpeakerTurn(0, 0, 3000), SpeakerTurn(1, 1000, 2000))
        val words = listOf(w("a", 0, 500), w("b", 1000, 1900), w("c", 1100, 2000), w("d", 2500, 2900))
        val r = aligner.align(words, turns)
        assertTrue(r.zipWithNext().all { (x, y) -> x.startMs <= y.startMs })
    }

    @Test
    fun `unsorted input words are sorted`() {
        val r = aligner.align(listOf(w("b", 600, 900), w("a", 0, 500)), emptyList())
        assertEquals("a b", r[0].text)
    }

    @Test
    fun `result is deterministic`() {
        val turns = listOf(SpeakerTurn(0, 0, 2000), SpeakerTurn(1, 1500, 4000))
        val words = (0 until 40).map { w("w$it", it * 100L, it * 100L + 80) }
        assertEquals(aligner.align(words, turns), aligner.align(words.reversed(), turns.reversed()))
    }
}
