package ru.neurosteno.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ресемплинг с антиалиасингом: полифазный FIR (windowed-sinc, окно Кайзера, beta = 8 -> ~-80 дБ).
 * Рациональное отношение inRate/outRate сводится к L/M; для каждой из L фаз заранее считается фильтр
 * (нормирован на единичное усиление по постоянной составляющей). Линейная интерполяция НЕ используется.
 *
 * Края сигнала считаются дополненными нулями. Длина результата = floor(n * outRate / inRate).
 */
object Resampler {
    private const val KAISER_BETA = 8.0
    private const val ZERO_CROSSINGS = 16

    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        require(inRate > 0 && outRate > 0) { "Некорректная частота: $inRate -> $outRate" }
        if (inRate == outRate || input.isEmpty()) return input.copyOf()

        val g = gcd(inRate, outRate)
        val up = outRate / g // L
        val down = inRate / g // M
        val ratio = outRate.toDouble() / inRate
        // cutoff (циклов на входной отсчёт): при понижении -- ниже nyquist выхода, иначе ниже nyquist входа
        val fc = 0.5 * min(1.0, ratio) * 0.96
        val halfLen = ZERO_CROSSINGS / (2 * fc) // в входных отсчётах
        val half = floor(halfLen).toInt() + 1
        val taps = 2 * half

        // Таблица фильтров по фазам: phase p соответствует дробному сдвигу frac = p / up
        val table = Array(up) { p ->
            val frac = p.toDouble() / up
            val h = DoubleArray(taps)
            var sum = 0.0
            for (k in 0 until taps) {
                val u = (k - half + 1) - frac // положение отсчёта относительно точки интерполяции (в входных отсчётах)
                val v = sinc(2 * fc * u) * 2 * fc * kaiser(u / halfLen)
                h[k] = v
                sum += v
            }
            FloatArray(taps) { (h[it] / sum).toFloat() }
        }

        val outLen = (input.size.toLong() * up / down).toInt()
        val out = FloatArray(outLen)
        val n = input.size
        for (i in 0 until outLen) {
            val pos = i.toLong() * down // положение в единицах 1/up входного отсчёта
            val base = (pos / up).toInt()
            val phase = (pos % up).toInt()
            val filter = table[phase]
            val start = base - half + 1
            var acc = 0f
            if (start >= 0 && start + taps <= n) {
                for (k in 0 until taps) acc += filter[k] * input[start + k]
            } else {
                for (k in 0 until taps) {
                    val idx = start + k
                    if (idx in 0 until n) acc += filter[k] * input[idx]
                }
            }
            out[i] = acc
        }
        return out
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun sinc(x: Double): Double = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

    /** Окно Кайзера на |x| <= 1 (вне -- 0). */
    private fun kaiser(x: Double): Double {
        if (abs(x) >= 1.0) return 0.0
        return bessel0(KAISER_BETA * sqrt(1 - x * x)) / bessel0(KAISER_BETA)
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-12 * sum && k < 200) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return max(sum, 1.0)
    }
}
