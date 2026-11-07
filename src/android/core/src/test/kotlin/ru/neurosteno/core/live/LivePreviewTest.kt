package ru.neurosteno.core.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Фейк движка: на каждый вызов accept отдаёт следующее заранее заданное обновление. */
class FakeStreamingAsr(private val script: List<LiveUpdate?>, private val tail: LiveUpdate? = null) : StreamingAsr {
    var accepted = 0
        private set
    var samplesSeen = 0
        private set
    var closed = false
        private set

    override fun accept(samples: FloatArray): LiveUpdate? {
        samplesSeen += samples.size
        return script.getOrNull(accepted++)
    }

    override fun finish(): LiveUpdate? = tail
    override fun close() { closed = true }
}

class LivePreviewTest {
    private val chunk = ShortArray(1600) { 1000 }

    @Test
    fun `partial hypothesis changes and becomes final phrase`() {
        val asr = FakeStreamingAsr(listOf(
            LiveUpdate("добрый"), LiveUpdate("добрый день"), LiveUpdate("", "добрый день коллеги"), LiveUpdate("начнём"),
        ))
        val p = LivePreview(asr)
        assertEquals(LiveState(partial = "добрый"), p.feed(chunk, chunk.size))
        assertEquals("добрый день", p.feed(chunk, chunk.size)!!.partial)
        val afterFinal = p.feed(chunk, chunk.size)!!
        assertEquals(listOf("добрый день коллеги"), afterFinal.finals)
        assertEquals("", afterFinal.partial)
        assertEquals("начнём", p.feed(chunk, chunk.size)!!.partial)
        assertEquals("Добрый день коллеги начнём", p.state.fullText())
    }

    @Test
    fun `unchanged hypothesis does not produce update`() {
        val p = LivePreview(FakeStreamingAsr(listOf(LiveUpdate("да"), LiveUpdate("да"), null)))
        assertTrue(p.feed(chunk, chunk.size) != null)
        assertNull(p.feed(chunk, chunk.size))
        assertNull(p.feed(chunk, chunk.size))
    }

    @Test
    fun `blank phrases are ignored and spaces are normalized`() {
        val p = LivePreview(FakeStreamingAsr(listOf(LiveUpdate("", "   "), LiveUpdate("  ", "привет   мир "))))
        assertNull(p.feed(chunk, chunk.size))
        val s = p.feed(chunk, chunk.size)!!
        assertEquals(listOf("привет мир"), s.finals)
    }

    @Test
    fun `finish turns tail and pending partial into final phrases`() {
        val asr = FakeStreamingAsr(listOf(LiveUpdate("конец записи")), tail = LiveUpdate("конец записи", null))
        val p = LivePreview(asr)
        p.feed(chunk, chunk.size)
        val s = p.finish()
        assertEquals(listOf("конец записи"), s.finals)
        assertEquals("", s.partial)
    }

    @Test
    fun `only the last keepPhrases are kept`() {
        val updates = (1..5).map { LiveUpdate("", "фраза $it") }
        val p = LivePreview(FakeStreamingAsr(updates), keepPhrases = 3)
        repeat(5) { p.feed(chunk, chunk.size) }
        assertEquals(listOf("фраза 3", "фраза 4", "фраза 5"), p.state.finals)
        assertEquals(2, p.state.droppedPhrases)
    }

    @Test
    fun `pcm is converted to float and empty input is skipped`() {
        val asr = FakeStreamingAsr(emptyList())
        val p = LivePreview(asr)
        assertNull(p.feed(chunk, 0))
        assertNull(p.feed(FloatArray(0)))
        assertEquals(0, asr.accepted)
        p.feed(chunk, 800)
        assertEquals(800, asr.samplesSeen)
    }

    @Test
    fun `close releases engine once and stops accepting audio`() {
        val asr = FakeStreamingAsr(listOf(LiveUpdate("раз")))
        val p = LivePreview(asr)
        p.close(); p.close()
        assertTrue(asr.closed)
        assertNull(p.feed(chunk, chunk.size))
        assertEquals(0, asr.accepted)
        assertFalse(p.finish().finals.isNotEmpty())
    }

    @Test
    fun `tidy collapses spaces and keeps case`() {
        assertEquals("привет МИР", LivePreview.tidy(" привет  МИР"))
        assertEquals("", LivePreview.tidy("   "))
        assertEquals("Привет мир", LiveState(finals = listOf("привет"), partial = "мир").fullText())
    }
}
