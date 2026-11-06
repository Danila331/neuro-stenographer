package ru.neurosteno.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import ru.neurosteno.core.audio.Pcm
import ru.neurosteno.core.audio.WavWriter

/**
 * Запись с микрофона: AudioRecord 16 кГц mono PCM16 -> WAV-файл (потоково, заголовок дописывается в [stop]).
 * Разрешение RECORD_AUDIO проверяет вызывающий код; без него [start] бросает [SecurityException]/[IOException].
 * [onLevel] получает пиковую амплитуду 0..1 для индикатора уровня.
 */
class AudioRecorder(private val outFile: File, private val onLevel: (Float) -> Unit = {}) {
    @Volatile private var recording = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    @Volatile private var failure: Throwable? = null
    @Volatile var recordedSamples: Long = 0
        private set

    val durationMs: Long get() = recordedSamples * 1000 / Pcm.TARGET_RATE
    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        check(!recording) { "Запись уже идёт" }
        val minBuf = AudioRecord.getMinBufferSize(Pcm.TARGET_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IOException("Микрофон недоступен (getMinBufferSize=$minBuf)")
        val bufSize = maxOf(minBuf * 2, Pcm.TARGET_RATE) // >= 0.5 с
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Pcm.TARGET_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("Не удалось инициализировать микрофон")
        }
        outFile.parentFile?.mkdirs()
        val w = WavWriter(outFile, Pcm.TARGET_RATE)
        record = rec
        writer = w
        recordedSamples = 0
        failure = null
        recording = true
        rec.startRecording()
        thread = Thread({
            val buf = ShortArray(Pcm.TARGET_RATE / 10) // 100 мс
            try {
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw IOException("AudioRecord.read=$n")
                    if (n > 0) {
                        w.write(buf, n)
                        recordedSamples += n
                        var peak = 0
                        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
                        onLevel(peak / 32768f)
                    }
                }
            } catch (t: Throwable) {
                failure = t
                recording = false
            }
        }, "audio-recorder").also { it.start() }
    }

    /** Останавливает запись и возвращает файл; бросает [IOException], если запись прервалась ошибкой. */
    @Synchronized
    fun stop(): File {
        recording = false
        thread?.join(2_000)
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { writer?.close() }
        writer = null
        failure?.let { throw IOException("Запись прервана: ${it.message}", it) }
        return outFile
    }
}
