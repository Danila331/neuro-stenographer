package ru.neurosteno.core.live

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Состояние сеанса живой расшифровки. */
sealed interface LiveStatus {
    /** Модель загружается (звук копится в очереди и будет догнан). */
    data object Loading : LiveStatus

    /** Идёт распознавание. [lagging] -- распознавание не успевает за речью (часть звука для превью пропущена). */
    data class Active(val lagging: Boolean = false) : LiveStatus

    /** Превью остановлено: запись продолжается и будет обработана как обычно. */
    data class Failed(val message: String) : LiveStatus

    /** Сеанс завершён (после [LiveSession.stop]). */
    data object Finished : LiveStatus
}

/**
 * Фоновый сеанс живой расшифровки (B13): отдельный поток читает очередь PCM из потока записи и кормит [LivePreview].
 * Поток записи никогда не блокируется: [offer] только копирует порцию в очередь (при переполнении старые порции
 * отбрасываются -- превью не обязано быть полным). Модель создаётся ВНУТРИ рабочего потока ([factory]); при ошибке
 * загрузки превью отключается, запись не страдает. [stop] дорасшифровывает хвост и ВЫГРУЖАЕТ модель до возврата
 * (полная обработка начинается сразу после остановки записи). Чистый Kotlin -- тестируется на фейке.
 */
class LiveSession(
    private val factory: () -> StreamingAsr,
    private val keepPhrases: Int = 200,
    /** Максимум порций в очереди (порция ~100 мс): ~60 с звука. */
    private val maxQueued: Int = 600,
    private val onUpdate: (LiveStatus, LiveState) -> Unit,
) {
    private class Chunk(val data: ShortArray, val n: Int)

    private val queue = LinkedBlockingQueue<Chunk>()
    private val stopMarker = Chunk(ShortArray(0), -1)
    private val done = CountDownLatch(1)
    @Volatile private var state = LiveState()
    @Volatile private var status: LiveStatus = LiveStatus.Loading
    @Volatile private var started = false
    @Volatile private var cancelled = false
    @Volatile private var lagging = false
    @Volatile private var audioSamples = 0L
    @Volatile private var busyNs = 0L
    @Volatile private var loadMs = 0L
    private var thread: Thread? = null

    /** Измерения для лога: длительность поданного звука, время работы движка и загрузки модели (RTF превью = busyMs / audioMs). */
    data class Stats(val audioMs: Long, val busyMs: Long, val loadMs: Long) {
        val rtf: Double get() = if (audioMs > 0) busyMs.toDouble() / audioMs else 0.0
    }

    val stats: Stats get() = Stats(audioSamples * 1000 / 16_000, busyNs / 1_000_000, loadMs)

    val currentState: LiveState get() = state
    val currentStatus: LiveStatus get() = status

    @Synchronized
    fun start() {
        check(!started) { "LiveSession уже запущена" }
        started = true
        publish(LiveStatus.Loading)
        thread = Thread(::run, "live-asr").also { it.isDaemon = true; it.start() }
    }

    /** Вызывается с потока записи; не блокируется. */
    fun offer(pcm: ShortArray, n: Int) {
        if (!started || cancelled || n <= 0 || status is LiveStatus.Failed || status == LiveStatus.Finished) return
        while (queue.size >= maxQueued) { queue.poll(); lagging = true }
        queue.offer(Chunk(pcm.copyOf(n), n))
    }

    /**
     * Конец записи: дорасшифровать хвост, выгрузить модель, вернуть итоговый текст превью. Ждёт не дольше [timeoutMs];
     * если поток не успел -- возвращает то, что есть (поток закончит и выгрузит модель сам).
     */
    fun stop(timeoutMs: Long = 3_000): LiveState {
        if (!started) return state
        queue.offer(stopMarker)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return state
    }

    /** Отмена записи: ничего не досчитывать, выгрузить модель. */
    fun cancel(timeoutMs: Long = 1_500) {
        if (!started) return
        cancelled = true
        queue.clear()
        queue.offer(stopMarker)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun run() {
        var preview: LivePreview? = null
        try {
            preview = try {
                val t0 = System.nanoTime()
                LivePreview(factory(), keepPhrases).also { loadMs = (System.nanoTime() - t0) / 1_000_000 }
            } catch (t: Throwable) {
                publish(LiveStatus.Failed(t.message ?: t.javaClass.simpleName))
                drainUntilStop()
                return
            }
            publish(LiveStatus.Active(false))
            while (true) {
                val c = queue.take()
                if (c === stopMarker) break
                if (cancelled) continue
                try {
                    val t0 = System.nanoTime()
                    val s = preview.feed(c.data, c.n)
                    busyNs += System.nanoTime() - t0
                    audioSamples += c.n
                    val lag = lagging && queue.size > 2
                    if (lag != (status as? LiveStatus.Active)?.lagging) publish(LiveStatus.Active(lag), s ?: state) else if (s != null) publish(status, s)
                    if (!lag) lagging = false
                } catch (t: Throwable) {
                    publish(LiveStatus.Failed(t.message ?: t.javaClass.simpleName))
                    break
                }
            }
            if (!cancelled && status !is LiveStatus.Failed) {
                try { state = preview.finish() } catch (_: Throwable) { /* хвост не критичен */ }
            }
        } finally {
            runCatching { preview?.close() }
            if (status !is LiveStatus.Failed) publish(LiveStatus.Finished)
            done.countDown()
        }
    }

    /** После сбоя загрузки/распознавания просто ждём конец записи, не накапливая очередь. */
    private fun drainUntilStop() {
        while (true) { val c = queue.take(); if (c === stopMarker) break }
    }

    private fun publish(s: LiveStatus, st: LiveState = state) {
        status = s
        state = st
        onUpdate(s, st)
    }
}
