package ru.neurosteno.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Грубая оценка SNR без опорного сигнала: разница между уровнем «громких» (речь, 90-й перцентиль)
 * и «тихих» (шум, 10-й перцентиль) 20-мс кадров в дБ. Нужна только чтобы решить, запускать ли денойзер.
 */
object SnrEstimator {
    private const val FRAME_MS = 20
    private const val SILENCE_DB = -90.0

    fun estimateDb(audio: AudioBuffer): Double {
        val frame = audio.sampleRate * FRAME_MS / 1000
        val n = audio.samples.size / frame
        if (n < 10) return Double.POSITIVE_INFINITY
        val levels = DoubleArray(n) { i ->
            var sum = 0.0
            val base = i * frame
            for (k in 0 until frame) {
                val v = audio.samples[base + k].toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / frame)
            if (rms <= 1e-9) SILENCE_DB else 20 * log10(rms)
        }
        levels.sort()
        val noise = levels[(n * 0.10).toInt().coerceIn(0, n - 1)]
        val speech = levels[(n * 0.90).toInt().coerceIn(0, n - 1)]
        if (speech <= SILENCE_DB) return Double.POSITIVE_INFINITY // полная тишина -- денойз не нужен
        return speech - noise
    }
}
