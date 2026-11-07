package ru.neurosteno.core.fragment

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.AudioDecoder
import ru.neurosteno.core.AudioInput
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.audio.WavDecoder

/**
 * Источник исходного аудио для повторной обработки фрагмента: отдаёт произвольные отрезки в mono 16 кГц, не требуя
 * держать в памяти всю запись (для WAV 16 кГц читается только нужный диапазон байт).
 * Реализации потокобезопасны не обязаны быть: [FragmentReprocessor] вызывает [read] последовательно.
 */
interface AudioSource {
    /** Длительность всей записи, мс. */
    val durationMs: Long

    /** Копия отрезка `[startMs, endMs)` (границы зажимаются в `[0, durationMs]`); буфер принадлежит вызывающему. */
    fun read(startMs: Long, endMs: Long): AudioBuffer

    companion object {
        /** Уже декодированное аудио. */
        fun of(audio: AudioBuffer): AudioSource = BufferAudioSource(audio)

        /** Сохранённый WAV (быстрый путь для PCM16 16 кГц; прочие WAV декодируются целиком один раз). */
        fun wav(file: File): AudioSource = WavFileAudioSource(file)

        /** Любой формат через декодер платформы (на Android -- `MediaCodecAudioDecoder`): запись декодируется целиком при первом чтении. */
        fun decoded(input: AudioInput, decoder: AudioDecoder, progress: ProgressListener = ProgressListener { _, _ -> }): AudioSource =
            LazyDecodedAudioSource { decoder.decode(input, progress) }
    }
}

class BufferAudioSource(private val audio: AudioBuffer) : AudioSource {
    override val durationMs: Long get() = audio.durationMs

    override fun read(startMs: Long, endMs: Long): AudioBuffer {
        val sr = audio.sampleRate
        val from = (startMs.coerceAtLeast(0) * sr / 1000).toInt().coerceIn(0, audio.samples.size)
        val to = (endMs.coerceAtLeast(0) * sr / 1000).toInt().coerceIn(from, audio.samples.size)
        return AudioBuffer(audio.samples.copyOfRange(from, to), sr)
    }
}

internal class LazyDecodedAudioSource(private val decode: () -> AudioBuffer) : AudioSource {
    private val delegate: BufferAudioSource by lazy { BufferAudioSource(decode()) }
    override val durationMs: Long get() = delegate.durationMs
    override fun read(startMs: Long, endMs: Long): AudioBuffer = delegate.read(startMs, endMs)
}

/**
 * WAV на диске. PCM16 с частотой 16 кГц (формат, в котором приложение сохраняет запись) читается по диапазону байт
 * через [RandomAccessFile]; любой другой WAV (другая частота/разрядность) декодируется [WavDecoder] целиком при первом чтении.
 */
class WavFileAudioSource(private val file: File) : AudioSource {
    private class Layout(val channels: Int, val dataStart: Long, val frames: Long)

    private val layout: Layout? = parseFastLayout(file)
    private val fallback: BufferAudioSource? by lazy { if (layout == null) BufferAudioSource(WavDecoder.decode(file)) else null }

    override val durationMs: Long get() = layout?.let { it.frames * 1000 / RATE } ?: fallback!!.durationMs

    override fun read(startMs: Long, endMs: Long): AudioBuffer {
        val l = layout ?: return fallback!!.read(startMs, endMs)
        val from = (startMs.coerceAtLeast(0) * RATE / 1000).coerceIn(0, l.frames)
        val to = (endMs.coerceAtLeast(0) * RATE / 1000).coerceIn(from, l.frames)
        val n = (to - from).toInt()
        if (n == 0) return AudioBuffer(FloatArray(0), RATE.toInt())
        val bytes = ByteArray(n * l.channels * 2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(l.dataStart + from * l.channels * 2)
            raf.readFully(bytes)
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(n)
        var p = 0
        for (i in 0 until n) {
            var acc = 0f
            for (c in 0 until l.channels) {
                acc += bb.getShort(p) / 32768f
                p += 2
            }
            out[i] = acc / l.channels
        }
        return AudioBuffer(out, RATE.toInt())
    }

    private companion object {
        const val RATE = 16_000L

        /** Раскладка для быстрого пути или null (формат не PCM16/16 кГц либо не разобрать -- тогда полный декодер). */
        fun parseFastLayout(file: File): Layout? = try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val head = ByteArray(12)
                if (len < 12) return@use null
                raf.readFully(head)
                if (!WavDecoder.isWav(head)) return@use null
                var pos = 12L
                var format = 0
                var channels = 0
                var rate = 0
                var bits = 0
                val hdr = ByteArray(8)
                while (pos + 8 <= len) {
                    raf.seek(pos)
                    raf.readFully(hdr)
                    val id = String(hdr, 0, 4, Charsets.ISO_8859_1)
                    val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val body = pos + 8
                    if (id == "fmt " && size >= 16 && body + 16 <= len) {
                        val f = ByteArray(16)
                        raf.readFully(f)
                        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                        format = bb.getShort(0).toInt() and 0xFFFF
                        channels = bb.getShort(2).toInt() and 0xFFFF
                        rate = bb.getInt(4)
                        bits = bb.getShort(14).toInt() and 0xFFFF
                    } else if (id == "data") {
                        if (format != 1 || bits != 16 || rate != RATE.toInt() || channels !in 1..2) return@use null
                        val dataLen = if (size == 0L || size == 0xFFFFFFFFL || body + size > len) len - body else size
                        return@use Layout(channels, body, dataLen / (2L * channels))
                    }
                    pos = body + size + (size and 1)
                }
                null
            }
        } catch (_: java.io.IOException) {
            null
        }
    }
}
