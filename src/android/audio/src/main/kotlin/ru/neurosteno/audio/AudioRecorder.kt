package ru.neurosteno.audio

import java.io.File
import java.io.IOException
import ru.neurosteno.core.audio.Pcm
import ru.neurosteno.core.audio.WavWriter

/**
 * Запись с микрофона: [PcmSource] 16 кГц mono PCM16 -> WAV-файл (потоково, заголовок дописывается в [stop]).
 * Разрешение RECORD_AUDIO проверяет вызывающий код; без него [start] бросает [SecurityException]/[IOException].
 * [onLevel] получает пиковую амплитуду 0..1 для индикатора уровня; [onPcm] -- каждую порцию (100 мс) звука для живой
 * расшифровки: вызывается на потоке записи и обязан возвращаться быстро (тяжёлую работу -- в свою очередь/поток).
 */
class AudioRecorder(
    private val outFile: File,
    private val onLevel: (Float) -> Unit = {},
    private val onPcm: ((ShortArray, Int) -> Unit)? = null,
) {
    @Volatile private var recording = false
    private var thread: Thread? = null
    private var source: PcmSource? = null
    private var writer: WavWriter? = null
    @Volatile private var failure: Throwable? = null
    @Volatile var recordedSamples: Long = 0
        private set

    val durationMs: Long get() = recordedSamples * 1000 / Pcm.TARGET_RATE
    val isRecording: Boolean get() = recording

    @Synchronized
    fun start() {
        check(!recording) { "Запись уже идёт" }
        val src = DebugAudio.sourceFactory?.invoke() ?: MicPcmSource()
        src.start()
        outFile.parentFile?.mkdirs()
        val w = try { WavWriter(outFile, Pcm.TARGET_RATE) } catch (t: Throwable) { src.stop(); throw t }
        source = src
        writer = w
        recordedSamples = 0
        failure = null
        recording = true
        thread = Thread({
            val buf = ShortArray(Pcm.TARGET_RATE / 10) // 100 мс
            try {
                while (recording) {
                    val n = src.read(buf)
                    if (n == PcmSource.END) break
                    if (n < 0) throw IOException("чтение звука: код $n")
                    if (n > 0) {
                        w.write(buf, n)
                        recordedSamples += n
                        var peak = 0
                        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
                        onLevel(peak / 32768f)
                        onPcm?.invoke(buf, n)
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
        source?.stop()
        source = null
        runCatching { writer?.close() }
        writer = null
        failure?.let { throw IOException("Запись прервана: ${it.message}", it) }
        return outFile
    }
}
