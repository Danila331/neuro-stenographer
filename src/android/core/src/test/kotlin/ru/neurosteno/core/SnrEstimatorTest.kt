package ru.neurosteno.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnrEstimatorTest {
    private fun signal(noiseAmp: Float, speechAmp: Float): AudioBuffer {
        val rnd = Random(1)
        val sr = 16_000
        val s = FloatArray(sr * 6) { i ->
            val speechOn = (i / (sr / 2)) % 2 == 0
            val tone = if (speechOn) speechAmp * sin(2 * PI * 220 * i / sr).toFloat() else 0f
            tone + noiseAmp * (rnd.nextFloat() * 2 - 1)
        }
        return AudioBuffer(s)
    }

    @Test
    fun `clean signal has high snr, noisy one is low`() {
        val clean = SnrEstimator.estimateDb(signal(0.001f, 0.5f))
        val noisy = SnrEstimator.estimateDb(signal(0.2f, 0.3f))
        assertTrue(clean > 30, "clean=$clean")
        assertTrue(noisy < 12, "noisy=$noisy")
    }

    @Test
    fun `silence and very short audio never trigger denoise`() {
        assertEquals(Double.POSITIVE_INFINITY, SnrEstimator.estimateDb(AudioBuffer(FloatArray(16_000 * 3))))
        assertEquals(Double.POSITIVE_INFINITY, SnrEstimator.estimateDb(AudioBuffer(FloatArray(100))))
    }
}
