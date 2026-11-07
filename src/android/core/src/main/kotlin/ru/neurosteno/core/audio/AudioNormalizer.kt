package ru.neurosteno.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.NormalizeGainConfig

/**
 * Пиковая нормализация громкости (`audio.normalize_gain`, BACKLOG B24) -- порт `bench_asr/audio_norm.py::normalize`
 * (режим `peak`): `g = target_peak - peak_dBFS`, усиление зажимается в `[-max_attenuation, +max_gain]` и не выше
 * `-1 dBFS - peak` (без клиппинга). Почти нулевой сигнал (пик < -85 dBFS) не трогаем: усиливать нечего.
 * Чистая функция: [gainDb] считает усиление, [apply] применяет его на месте к буферу, которым владеет вызывающий.
 */
object AudioNormalizer {
    private const val EPS = 1e-9
    private const val CEIL_DB = -1.0

    private fun db(x: Double) = 20 * log10(maxOf(x, EPS))

    /** Усиление в дБ для сигнала с пиком [peak] (0..1); 0, если нормализация выключена или нечего усиливать. */
    fun gainDb(peak: Double, cfg: NormalizeGainConfig): Double {
        if (!cfg.active) return 0.0
        val pkDb = db(peak)
        if (pkDb < cfg.skipIfPeakBelowDbfs) return 0.0
        var g = cfg.peakDbfs - pkDb
        g = g.coerceIn(-cfg.maxAttenuationDb, cfg.maxGainDb)
        return minOf(g, CEIL_DB - pkDb)
    }

    fun peak(samples: FloatArray): Double {
        var p = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > p) p = a
        }
        return p.toDouble()
    }

    /** Применяет нормализацию к [audio] на месте; возвращает применённое усиление в дБ. */
    fun apply(audio: AudioBuffer, cfg: NormalizeGainConfig): Double {
        if (audio.samples.isEmpty()) return 0.0
        val g = gainDb(peak(audio.samples), cfg)
        if (g == 0.0) return 0.0
        val k = 10.0.pow(g / 20).toFloat()
        val x = audio.samples
        for (i in x.indices) x[i] *= k
        return g
    }
}
