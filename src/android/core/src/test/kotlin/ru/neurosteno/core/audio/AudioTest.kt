package ru.neurosteno.core.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.neurosteno.core.AudioBuffer

class AudioTest {
    private fun sine(freq: Double, rate: Int, seconds: Double, amp: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    /** RMS средней части (без краёв, где фильтр ещё не прогрет). */
    private fun rms(x: FloatArray): Double {
        val a = x.size / 10
        val b = x.size - a
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a))
    }

    @Test
    fun `length is floor of n times ratio`() {
        assertEquals(16_000, Resampler.resample(FloatArray(44_100), 44_100, 16_000).size)
        assertEquals(16_000, Resampler.resample(FloatArray(48_000), 48_000, 16_000).size)
        assertEquals(16_000, Resampler.resample(FloatArray(8_000), 8_000, 16_000).size)
        assertEquals(0, Resampler.resample(FloatArray(0), 44_100, 16_000).size)
    }

    @Test
    fun `passband tone keeps amplitude`() {
        for (rate in listOf(44_100, 48_000, 22_050, 8_000, 32_000)) {
            val out = Resampler.resample(sine(1000.0, rate, 1.0), rate, 16_000)
            val ratio = rms(out) / rms(sine(1000.0, rate, 1.0))
            assertEquals(1.0, ratio, 0.02, "rate=$rate ratio=$ratio")
        }
    }

    @Test
    fun `tone above new nyquist is strongly attenuated (anti-aliasing, not linear interpolation)`() {
        // 12 кГц при 48 кГц -> без ФНЧ алиасится в 4 кГц с полной амплитудой
        for ((freq, rate) in listOf(12_000.0 to 48_000, 15_000.0 to 44_100, 10_000.0 to 32_000)) {
            val out = Resampler.resample(sine(freq, rate, 1.0), rate, 16_000)
            val att = 20 * log10(rms(out) / rms(sine(freq, rate, 1.0)) + 1e-12)
            assertTrue(att < -50, "freq=$freq rate=$rate attenuation=$att dB")
        }
    }

    @Test
    fun `same rate returns copy and dc is preserved`() {
        val x = FloatArray(1000) { 0.25f }
        assertEquals(x.toList(), Resampler.resample(x, 16_000, 16_000).toList())
        val out = Resampler.resample(x, 44_100, 16_000)
        assertEquals(0.25f, out[out.size / 2], 1e-3f)
    }

    @Test
    fun `pcm helpers`() {
        val mono = Pcm.shortsToMono(shortArrayOf(16384, -16384, 8192, 8192), 4, 2)
        assertEquals(listOf(0f, 0.25f), mono.toList())
        assertEquals(listOf(0.5f), Pcm.shortsToMono(shortArrayOf(16384), 1, 1).toList())
        val bad = floatArrayOf(Float.NaN, 5f, -5f, 0.1f)
        Pcm.sanitize(bad)
        assertEquals(listOf(0f, 1f, -1f, 0.1f), bad.toList())
        assertEquals(listOf<Short>(32767, -32768, 0), Pcm.floatsToShorts(floatArrayOf(2f, -2f, 0f)).toList())
    }

    @Test
    fun `wav writer and decoder round trip`(@TempDir dir: File) {
        val f = File(dir, "a.wav")
        val src = sine(440.0, 16_000, 0.5)
        WavWriter.write(f, AudioBuffer(src))
        assertEquals(44 + src.size * 2L, f.length())
        val back = WavDecoder.decode(f)
        assertEquals(src.size, back.samples.size)
        assertEquals(16_000, back.sampleRate)
        for (i in src.indices step 97) assertEquals(src[i], back.samples[i], 1e-3f)
    }

    private fun wav(format: Int, bits: Int, channels: Int, rate: Int, data: ByteArray, declaredSize: Long = data.size.toLong()): ByteArray {
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        h.putShort(format.toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bits / 8)
        h.putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(declaredSize.toInt())
        out.write(h.array()); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `wav decoder handles stereo 8bit 24bit float and resampling`() {
        val stereo = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(16384).putShort(0).putShort(-16384).putShort(0).array()
        assertEquals(listOf(0.25f, -0.25f), WavDecoder.decode(wav(1, 16, 2, 16_000, stereo)).samples.toList())

        val u8 = WavDecoder.decode(wav(1, 8, 1, 16_000, byteArrayOf(-128, 0, 127.toByte()))).samples
        assertEquals(0f, u8[0], 1e-6f); assertEquals(-1f, u8[1], 1e-6f); assertEquals(-1f / 128, u8[2], 1e-6f)

        val f32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-0.5f).array()
        assertEquals(listOf(0.5f, -0.5f), WavDecoder.decode(wav(3, 32, 1, 16_000, f32)).samples.toList())

        val s24 = byteArrayOf(0, 0, 0x40) // 0x400000 = 0.5
        assertEquals(0.5f, WavDecoder.decode(wav(1, 24, 1, 16_000, s24)).samples[0], 1e-6f)

        val long = ByteBuffer.allocate(48_000 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48_000) { long.putShort(0) }
        assertEquals(16_000, WavDecoder.decode(wav(1, 16, 1, 48_000, long.array())).samples.size)
    }

    @Test
    fun `wav decoder tolerates streamed header and rejects garbage`() {
        val pcm = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(10) { putShort(1000) } }.array()
        assertEquals(10, WavDecoder.decode(wav(1, 16, 1, 16_000, pcm, declaredSize = 0xFFFFFFFFL)).samples.size)
        assertEquals(10, WavDecoder.decode(wav(1, 16, 1, 16_000, pcm, declaredSize = 0)).samples.size)
        assertThrows<IllegalArgumentException> { WavDecoder.decode(ByteArray(100)) }
        assertThrows<IllegalArgumentException> { WavDecoder.decode("RIFF....WAVE".toByteArray()) }
    }
}
