package ru.neurosteno.core.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineLayoutTest {
    private fun u(sp: Int, s: Long, e: Long, added: Boolean = false) = TimelineUtterance("speaker_$sp", s, e, added)

    @Test
    fun `segments map time to pixels proportionally`() {
        val t = TimelineLayout(listOf(u(1, 0, 10_000), u(2, 10_000, 20_000)), durationMs = 20_000, widthPx = 400f)
        assertEquals(2, t.segments.size)
        assertEquals(0f, t.segments[0].x0, 0.01f)
        assertEquals(200f, t.segments[0].x1, 0.01f)
        assertEquals(200f, t.segments[1].x0, 0.01f)
        assertEquals(400f, t.segments[1].x1, 0.01f)
        assertEquals(1, t.lanes)
        assertTrue(t.segments.all { it.lane == 0 })
    }

    @Test
    fun `x and time are inverse and clamped`() {
        val t = TimelineLayout(listOf(u(1, 0, 60_000)), durationMs = 60_000, widthPx = 600f)
        assertEquals(30_000, t.xToMs(300f))
        assertEquals(300f, t.msToX(30_000), 0.01f)
        assertEquals(0, t.xToMs(-50f))
        assertEquals(60_000, t.xToMs(9_999f))
        assertEquals(600f, t.msToX(90_000), 0.01f)
        for (ms in listOf(0L, 1234L, 59_999L)) assertEquals(ms.toDouble(), t.xToMs(t.msToX(ms)).toDouble(), 100.0)
    }

    @Test
    fun `overlapping utterances go to separate lanes, touching ones share a lane`() {
        val t = TimelineLayout(listOf(u(1, 0, 8_000), u(2, 5_000, 12_000), u(1, 12_000, 15_000), u(3, 6_000, 9_000)), 15_000, 300f)
        val byStart = t.segments.associateBy { it.startMs to it.speakerId }
        assertEquals(0, byStart.getValue(0L to "speaker_1").lane)
        assertEquals(1, byStart.getValue(5_000L to "speaker_2").lane)
        assertEquals(2, byStart.getValue(6_000L to "speaker_3").lane)
        assertEquals(0, byStart.getValue(12_000L to "speaker_1").lane) // освободилась первая дорожка
        assertEquals(3, t.lanes)
    }

    @Test
    fun `lanes are capped and extra overlaps stay visible on last lane`() {
        val many = (1..6).map { u(it, 0, 10_000) }
        val t = TimelineLayout(many, 10_000, 100f, maxLanes = 3)
        assertEquals(3, t.lanes)
        assertTrue(t.segments.all { it.lane <= 2 })
        assertEquals(6, t.segments.size)
    }

    @Test
    fun `tiny segments keep minimum width and stay inside the strip`() {
        val t = TimelineLayout(listOf(u(1, 100, 110), u(1, 59_990, 60_000)), 60_000, 300f, minSegmentPx = 2f)
        assertTrue(t.segments.all { it.x1 - it.x0 >= 2f - 0.001f })
        assertTrue(t.segments.all { it.x1 <= 300f + 2f })
    }

    @Test
    fun `added flag is carried and empty or zero-length input is safe`() {
        val t = TimelineLayout(listOf(u(1, 0, 1_000, added = true), u(2, 500, 500)), 5_000, 100f)
        assertEquals(1, t.segments.size)
        assertTrue(t.segments[0].added)
        val e = TimelineLayout(emptyList(), 0, 100f)
        assertEquals(0, e.segments.size)
        assertEquals(1, e.lanes)
        assertEquals(0, e.xToMs(50f))
    }

    @Test
    fun `duration shorter than last utterance extends the scale`() {
        val t = TimelineLayout(listOf(u(1, 0, 20_000)), durationMs = 10_000, widthPx = 200f)
        assertEquals(200f, t.segments[0].x1, 0.01f)
        assertEquals(20_000, t.xToMs(200f))
    }

    @Test
    fun `hit test returns the narrowest segment under x`() {
        val t = TimelineLayout(listOf(u(1, 0, 10_000), u(2, 2_000, 4_000)), 10_000, 100f)
        assertEquals(1, t.segmentAt(30f)) // вложенная короткая реплика
        assertEquals(0, t.segmentAt(80f))
        assertEquals(-1, TimelineLayout(listOf(u(1, 0, 1_000)), 10_000, 100f).segmentAt(90f))
    }

    @Test
    fun `height grows with lanes`() {
        assertEquals(12f, TimelineLayout.heightDp(1))
        assertEquals(26f, TimelineLayout.heightDp(2))
    }
}
