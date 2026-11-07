package ru.neurosteno.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import ru.neurosteno.core.audio.Pcm
import ru.neurosteno.core.audio.WavDecoder

/** Источник PCM16 16 кГц mono для [AudioRecorder]: микрофон или (только для отладки) WAV-файл, поданный как микрофон. */
interface PcmSource {
    /** Готовит и запускает источник; бросает [IOException]/[SecurityException], если микрофон недоступен. */
    fun start()

    /** Блокирующее чтение; число прочитанных отсчётов, отрицательное -- ошибка, [END] -- источник закончился. */
    fun read(buf: ShortArray): Int
    fun stop()

    companion object { const val END = -1000 }
}

/** Микрофон устройства (AudioRecord, 16 кГц mono). */
class MicPcmSource : PcmSource {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val minBuf = AudioRecord.getMinBufferSize(Pcm.TARGET_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IOException("Микрофон недоступен (getMinBufferSize=$minBuf)")
        val bufSize = maxOf(minBuf * 2, Pcm.TARGET_RATE) // >= 0.5 с
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, Pcm.TARGET_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("Не удалось инициализировать микрофон")
        }
        record = rec
        rec.startRecording()
    }

    override fun read(buf: ShortArray): Int = record?.read(buf, 0, buf.size) ?: PcmSource.END

    override fun stop() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }
}

/**
 * ОТЛАДКА: подаёт WAV-файл в реальном времени, как будто это микрофон (эмулятор без звукового ввода; проверка живой расшифровки).
 * Включается только отладочной сборкой через [DebugAudio]; в обычной работе не используется.
 */
class WavFilePcmSource(private val file: File, private val realtime: Boolean = true) : PcmSource {
    private var samples: ShortArray = ShortArray(0)
    private var pos = 0
    private var startNs = 0L

    override fun start() {
        val audio = WavDecoder.decode(file)
        require(audio.sampleRate == Pcm.TARGET_RATE) { "WAV для подачи как микрофон должен быть 16 кГц (${audio.sampleRate})" }
        samples = Pcm.floatsToShorts(audio.samples)
        pos = 0
        startNs = System.nanoTime()
    }

    override fun read(buf: ShortArray): Int {
        if (pos >= samples.size) return PcmSource.END
        val n = minOf(buf.size, samples.size - pos)
        if (realtime) {
            // не быстрее реального времени: отсчёт pos+n доступен через (pos+n)/16000 с после старта
            val dueNs = startNs + (pos + n) * 1_000_000_000L / Pcm.TARGET_RATE
            val waitMs = (dueNs - System.nanoTime()) / 1_000_000
            if (waitMs > 0) Thread.sleep(waitMs)
        }
        System.arraycopy(samples, pos, buf, 0, n)
        pos += n
        return n
    }

    override fun stop() { samples = ShortArray(0) }
}

/** Точка подмены источника звука для отладочной сборки (устанавливает только `src/debug`); в обычной работе `null` -- микрофон. */
object DebugAudio {
    @Volatile var sourceFactory: (() -> PcmSource)? = null
}
