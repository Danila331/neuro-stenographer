package ru.neurosteno.core.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ru.neurosteno.core.AudioBuffer

/** Потоковая запись WAV (PCM16 mono). Заголовок дописывается при [close]; незакрытый файл остаётся читаемым с оговорками. */
class WavWriter(file: File, private val sampleRate: Int = Pcm.TARGET_RATE, private val channels: Int = 1) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    @Synchronized
    fun write(samples: ShortArray, length: Int = samples.size) {
        check(!closed) { "WavWriter закрыт" }
        val bb = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) bb.putShort(samples[i])
        raf.write(bb.array())
        dataBytes += length * 2L
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    private fun header(dataLen: Long): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt((36 + dataLen).coerceAtMost(0xFFFFFFFFL).toInt())
        bb.put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        bb.putShort(1).putShort(channels.toShort()).putInt(sampleRate)
        bb.putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen.coerceAtMost(0xFFFFFFFFL).toInt())
        return bb.array()
    }

    companion object {
        fun write(file: File, audio: AudioBuffer) {
            WavWriter(file, audio.sampleRate).use { it.write(Pcm.floatsToShorts(audio.samples)) }
        }
    }
}

/**
 * Чистый Kotlin-декодер WAV (PCM 8/16/24/32 бит, IEEE float 32/64, WAVE_FORMAT_EXTENSIBLE) -> моно 16 кГц.
 * Используется как быстрый путь и как запасной, если MediaCodec не справился. Бросает [IllegalArgumentException] на мусоре.
 */
object WavDecoder {
    fun isWav(head: ByteArray): Boolean =
        head.size >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE"

    fun decode(bytes: ByteArray): AudioBuffer {
        require(isWav(bytes)) { "Не WAV (нет RIFF/WAVE)" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0L
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt() and 0xFFFF
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt() and 0xFFFF
                if (format == 0xFFFE && size >= 26 && body + 26 <= bytes.size) format = bb.getShort(body + 24).toInt() and 0xFFFF
            } else if (id == "data") {
                dataStart = body
                // размер 0/0xFFFFFFFF/выходящий за файл -- поток без финализации: берём всё до конца
                dataLen = if (size == 0L || size == 0xFFFFFFFFL || body + size > bytes.size) (bytes.size - body).toLong() else size
                break
            }
            pos = (body + size + (size and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        require(dataStart >= 0 && channels > 0 && rate > 0) { "Повреждённый WAV (нет fmt/data)" }
        val bytesPerSample = bits / 8
        require(bytesPerSample in 1..8) { "Неподдерживаемая разрядность: $bits" }
        val frames = (dataLen / (bytesPerSample * channels)).toInt()
        val mono = FloatArray(frames)
        var p = dataStart
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += when {
                    format == 3 && bits == 32 -> bb.getFloat(p)
                    format == 3 && bits == 64 -> bb.getDouble(p).toFloat()
                    format == 1 && bits == 8 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                    format == 1 && bits == 16 -> bb.getShort(p) / 32768f
                    format == 1 && bits == 24 -> {
                        val v = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    format == 1 && bits == 32 -> bb.getInt(p) / 2147483648f
                    else -> throw IllegalArgumentException("Неподдерживаемый формат WAV: tag=$format bits=$bits")
                }
                p += bytesPerSample
            }
            mono[i] = acc / channels
        }
        Pcm.sanitize(mono)
        return Pcm.to16k(mono, rate)
    }

    fun decode(file: File): AudioBuffer = decode(file.readBytes())
}
