package ru.neurosteno.app.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.app.util.ProcessingQueue.Submit

class ProcessingQueueTest {
    @Test
    fun firstStartsSecondWaitsAndNothingIsLost() {
        val q = ProcessingQueue<String>(capacity = 3)
        assertEquals(Submit.Start, q.submit("a"))
        assertEquals(Submit.Queued(1), q.submit("b"))
        assertEquals(Submit.Queued(2), q.submit("c"))
        assertEquals(listOf("b", "c"), q.snapshot())
        assertEquals("b", q.release())
        assertEquals("c", q.release())
        assertNull(q.release())
        assertFalse(q.isBusy)
    }

    @Test
    fun overflowIsAnExplicitRefusal() {
        val q = ProcessingQueue<Int>(capacity = 1)
        assertEquals(Submit.Start, q.submit(1))
        assertEquals(Submit.Queued(1), q.submit(2))
        assertEquals(Submit.Full, q.submit(3)) // не принято -- вызывающий обязан сообщить маркером error
        assertEquals(listOf(2), q.snapshot())
    }

    @Test
    fun slotTakenByFragmentOrSummaryMakesFileRequestsWait() {
        val q = ProcessingQueue<String>()
        assertTrue(q.tryAcquire())
        assertFalse(q.tryAcquire())
        assertEquals(Submit.Queued(1), q.submit("file"))
        assertEquals("file", q.release()) // фрагмент закончился -- следующий файл стартует, слот остаётся занятым
        assertTrue(q.isBusy)
    }

    @Test
    fun drainReturnsWaitingRequests() {
        val q = ProcessingQueue<String>()
        q.submit("a"); q.submit("b"); q.submit("c")
        assertEquals(listOf("b", "c"), q.drain())
        assertNull(q.release())
    }

    @Test
    fun concurrentSubmitsStartExactlyOnceAndLoseNothing() {
        repeat(50) {
            val q = ProcessingQueue<Int>(capacity = 100)
            val starts = AtomicInteger()
            val queued = AtomicInteger()
            val pool = Executors.newFixedThreadPool(8)
            val go = CountDownLatch(1)
            repeat(40) { i ->
                pool.execute {
                    go.await()
                    when (q.submit(i)) {
                        Submit.Start -> starts.incrementAndGet()
                        is Submit.Queued -> queued.incrementAndGet()
                        Submit.Full -> error("lost")
                    }
                }
            }
            go.countDown()
            pool.shutdown()
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, starts.get())
            assertEquals(39, queued.get())
            var drained = 0
            while (q.release() != null) drained++
            assertEquals(39, drained)
        }
    }
}
