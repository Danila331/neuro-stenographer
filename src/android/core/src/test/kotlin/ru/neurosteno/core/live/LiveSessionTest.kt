package ru.neurosteno.core.live

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveSessionTest {
    private val chunk = ShortArray(1600) { 500 }

    private fun collector() = CopyOnWriteArrayList<Pair<LiveStatus, LiveState>>()

    @Test
    fun `audio is fed to engine in order, tail becomes final phrase, model is unloaded on stop`() {
        val asr = FakeStreamingAsr(
            listOf(LiveUpdate("привет"), LiveUpdate("", "привет мир"), LiveUpdate("как")),
            tail = LiveUpdate("как дела", null),
        )
        val log = collector()
        val s = LiveSession({ asr }, onUpdate = { st, state -> log += st to state })
        s.start()
        repeat(3) { s.offer(chunk, chunk.size) }
        val result = s.stop()
        assertEquals(listOf("привет мир", "как дела"), result.finals)
        assertEquals("", result.partial)
        assertTrue(asr.closed, "модель должна быть выгружена до возврата из stop")
        assertEquals(3, asr.accepted)
        assertEquals(LiveStatus.Finished, s.currentStatus)
        assertEquals(300, s.stats.audioMs) // 3 порции по 1600 отсчётов = 300 мс
        assertTrue(s.stats.busyMs >= 0 && s.stats.rtf >= 0.0)
        assertTrue(log.any { it.first is LiveStatus.Active })
        assertEquals(LiveStatus.Loading, log.first().first)
    }

    @Test
    fun `slow model load keeps queued audio and catches up`() {
        val gate = CountDownLatch(1)
        val asr = FakeStreamingAsr(listOf(LiveUpdate("один"), LiveUpdate("два")), tail = null)
        val s = LiveSession({ gate.await(2, TimeUnit.SECONDS); asr }, onUpdate = { _, _ -> })
        s.start()
        s.offer(chunk, chunk.size); s.offer(chunk, chunk.size) // пока модель грузится
        assertEquals(LiveStatus.Loading, s.currentStatus)
        gate.countDown()
        val r = s.stop()
        assertEquals(2, asr.accepted)
        assertEquals("два", r.finals.single()) // незавершённая гипотеза на stop превращается в финальную
    }

    @Test
    fun `model load failure disables preview but never blocks recording`() {
        val log = collector()
        val s = LiveSession({ throw IllegalStateException("нет файлов модели") }, onUpdate = { st, state -> log += st to state })
        s.start()
        repeat(5) { s.offer(chunk, chunk.size) }
        val r = s.stop()
        assertTrue(r.isEmpty)
        val failed = s.currentStatus as LiveStatus.Failed
        assertTrue(failed.message.contains("нет файлов"))
    }

    @Test
    fun `engine error mid-stream switches to Failed and still unloads model`() {
        val asr = object : StreamingAsr {
            var closed = false
            var calls = 0
            override fun accept(samples: FloatArray): LiveUpdate? { if (++calls == 2) error("сбой ONNX"); return LiveUpdate("ок") }
            override fun finish(): LiveUpdate? = null
            override fun close() { closed = true }
        }
        val s = LiveSession({ asr }, onUpdate = { _, _ -> })
        s.start()
        repeat(4) { s.offer(chunk, chunk.size) }
        s.stop()
        assertTrue(s.currentStatus is LiveStatus.Failed)
        assertTrue(asr.closed)
    }

    @Test
    fun `queue overflow drops oldest audio instead of growing`() {
        val gate = CountDownLatch(1)
        val asr = FakeStreamingAsr(emptyList())
        val s = LiveSession({ gate.await(2, TimeUnit.SECONDS); asr }, maxQueued = 5, onUpdate = { _, _ -> })
        s.start()
        repeat(20) { s.offer(chunk, chunk.size) }
        gate.countDown()
        s.stop()
        assertTrue(asr.accepted <= 6, "принято ${asr.accepted}")
        assertTrue(asr.accepted >= 5)
    }

    @Test
    fun `cancel unloads model without processing the rest`() {
        val asr = FakeStreamingAsr(listOf(LiveUpdate("a"), LiveUpdate("b")), tail = LiveUpdate("хвост"))
        val s = LiveSession({ asr }, onUpdate = { _, _ -> })
        s.start()
        s.offer(chunk, chunk.size)
        s.cancel()
        assertTrue(asr.closed)
        assertTrue(s.currentState.finals.none { it == "хвост" })
    }

    @Test
    fun `stop without start and offer after stop are harmless`() {
        val s = LiveSession({ FakeStreamingAsr(emptyList()) }, onUpdate = { _, _ -> })
        s.offer(chunk, chunk.size)
        assertTrue(s.stop().isEmpty)
        val s2 = LiveSession({ FakeStreamingAsr(emptyList()) }, onUpdate = { _, _ -> })
        s2.start(); s2.stop()
        s2.offer(chunk, chunk.size)
        assertEquals(LiveStatus.Finished, s2.currentStatus)
    }
}
