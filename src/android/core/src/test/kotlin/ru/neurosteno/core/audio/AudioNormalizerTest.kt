package ru.neurosteno.core.audio

import kotlin.math.abs
import kotlin.math.log10
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.NormalizeGainConfig

class AudioNormalizerTest {
    private val peak = NormalizeGainConfig(enabled = true, mode = "peak", peakDbfs = -1.0, maxGainDb = 40.0)

    private fun peakDb(x: FloatArray) = 20 * log10(AudioNormalizer.peak(x))

    @Test
    fun `quiet recording is amplified to target peak`() {
        val x = FloatArray(1000) { if (it % 2 == 0) 0.0112f else -0.004f } // пик ≈ -39 dBFS
        val g = AudioNormalizer.apply(AudioBuffer(x), peak)
        assertEquals(38.0, g, 0.2)
        assertEquals(-1.0, peakDb(x), 0.01)
    }

    @Test
    fun `gain is limited by max_gain_db`() {
        val x = FloatArray(100) { 0.0005f } // -66 dBFS -> нужно +65, разрешено +40
        assertEquals(40.0, AudioNormalizer.apply(AudioBuffer(x), peak), 1e-6)
        assertEquals(-26.0, peakDb(x), 0.05)
    }

    @Test
    fun `loud recording is attenuated at most by max attenuation and never clips`() {
        val x = FloatArray(100) { 1.0f }
        val g = AudioNormalizer.apply(AudioBuffer(x), peak)
        assertEquals(-1.0, g, 1e-6) // 0 dBFS -> -1 dBFS (меньше предела 6 дБ)
        assertTrue(x.all { abs(it) <= 0.8913f })
        val y = FloatArray(10) { 0.9999f }
        assertTrue(AudioNormalizer.apply(AudioBuffer(y), peak) >= -6.0)
    }

    @Test
    fun `off by default, unknown mode and near-zero signal are untouched`() {
        val src = floatArrayOf(0.01f, -0.02f)
        val a = src.copyOf()
        assertEquals(0.0, AudioNormalizer.apply(AudioBuffer(a), NormalizeGainConfig()), 0.0)
        assertEquals(src.toList(), a.toList())
        val b = src.copyOf()
        assertEquals(0.0, AudioNormalizer.apply(AudioBuffer(b), NormalizeGainConfig(enabled = true, mode = "rms")), 0.0)
        // mode=peak, но enabled=false (как в config/tiers/*.json) -- ничего не делаем
        val c = floatArrayOf(0.01f)
        assertEquals(0.0, AudioNormalizer.apply(AudioBuffer(c), NormalizeGainConfig(enabled = false, mode = "peak")), 0.0)
        val silent = FloatArray(10) { 1e-5f } // -100 dBFS
        assertEquals(0.0, AudioNormalizer.apply(AudioBuffer(silent), peak), 0.0)
        assertEquals(0.0, AudioNormalizer.apply(AudioBuffer(FloatArray(0)), peak), 0.0)
    }

    @Test
    fun `matches the python reference formula on a sample vector`() {
        // audio_norm.normalize(peak): g = clip(-1 - db(pk), -6, 40), g = min(g, -1 - db(pk)); pk = 0.02 -> db = -33.979 -> g = 32.979
        assertEquals(32.979, AudioNormalizer.gainDb(0.02, peak), 0.001)
    }
}
