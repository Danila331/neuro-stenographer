package ru.neurosteno.core.fragment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.neurosteno.core.TimeSpan

class SpeakerMatcherTest {
    private fun e(vararg v: Float) = v
    private val m = SpeakerMatcher(threshold = 0.5)

    @Test
    fun `cosine basics`() {
        assertEquals(1.0, SpeakerMatcher.cosine(e(1f, 2f), e(2f, 4f)), 1e-9)
        assertEquals(0.0, SpeakerMatcher.cosine(e(1f, 0f), e(0f, 1f)), 1e-9)
        assertEquals(0.0, SpeakerMatcher.cosine(e(0f, 0f), e(1f, 1f)), 1e-9)
        assertEquals(0.0, SpeakerMatcher.cosine(FloatArray(0), e(1f)), 1e-9)
    }

    @Test
    fun `best embedding wins and assignment is one-to-one`() {
        val locals = listOf(
            SpeakerMatcher.Local(0, e(1f, 0.1f), listOf(TimeSpan(0, 1000))),
            SpeakerMatcher.Local(1, e(0.9f, 0.6f), listOf(TimeSpan(1000, 2000))),
        )
        val globals = listOf(SpeakerMatcher.Global(1, e(1f, 0f), emptyList()), SpeakerMatcher.Global(2, e(0f, 1f), emptyList()))
        val r = m.match(locals, globals).associateBy { it.localId }
        assertEquals(1, r.getValue(0).globalIndex)
        assertEquals(SpeakerMatcher.Method.EMBEDDING, r.getValue(0).method)
        // local 1 тоже ближе к global 1 (cos 0.83 >= 0.5), но он занят -> остаётся global 2 (cos 0.55 >= 0.5)
        assertEquals(2, r.getValue(1).globalIndex)
    }

    @Test
    fun `below threshold with embeddings on both sides is a new speaker even if time overlaps`() {
        val locals = listOf(SpeakerMatcher.Local(0, e(0f, 1f), listOf(TimeSpan(0, 1000))))
        val globals = listOf(SpeakerMatcher.Global(1, e(1f, 0f), listOf(TimeSpan(0, 1000))))
        val r = m.match(locals, globals).single()
        assertNull(r.globalIndex)
        assertEquals(SpeakerMatcher.Method.NEW, r.method)
    }

    @Test
    fun `time overlap is used when the local has no embedding`() {
        val locals = listOf(SpeakerMatcher.Local(0, null, listOf(TimeSpan(1000, 3000))))
        val globals = listOf(
            SpeakerMatcher.Global(1, e(1f, 0f), listOf(TimeSpan(0, 1200))),
            SpeakerMatcher.Global(2, null, listOf(TimeSpan(1200, 4000))),
        )
        val r = m.match(locals, globals).single()
        assertEquals(2, r.globalIndex)
        assertEquals(SpeakerMatcher.Method.TIME_OVERLAP, r.method)
        assertEquals(0.9, r.score!!, 1e-9)
    }

    @Test
    fun `too little overlap means a new speaker`() {
        val locals = listOf(SpeakerMatcher.Local(0, null, listOf(TimeSpan(0, 10_000))))
        val globals = listOf(SpeakerMatcher.Global(1, null, listOf(TimeSpan(9_000, 9_500))))
        assertNull(m.match(locals, globals).single().globalIndex)
    }

    @Test
    fun `local with embedding may take a global without one by time`() {
        val locals = listOf(SpeakerMatcher.Local(0, e(1f, 0f), listOf(TimeSpan(0, 1000))))
        val globals = listOf(SpeakerMatcher.Global(1, null, listOf(TimeSpan(0, 1000))))
        assertEquals(1, m.match(locals, globals).single().globalIndex)
    }

    @Test
    fun `no globals - everybody is new`() {
        val r = m.match(listOf(SpeakerMatcher.Local(0, e(1f), emptyList()), SpeakerMatcher.Local(1, null, emptyList())), emptyList())
        assertEquals(listOf(null, null), r.map { it.globalIndex })
    }
}
