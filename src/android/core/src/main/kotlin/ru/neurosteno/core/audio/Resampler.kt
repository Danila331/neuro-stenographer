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
    /** Одноразовый ресемплинг всего сигнала. */
    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        require(inRate > 0 && outRate > 0) { "Некорректная частота: $inRate -> $outRate" }
        if (inRate == outRate || input.isEmpty()) return input.copyOf()
        val s = ResamplerStream(inRate, outRate)
        val head = s.push(input)
        val tail = s.finish()
        if (tail.isEmpty()) return head
        return head + tail
    }
}

/**
 * Потоковый вариант: [push] принимает входные блоки произвольной длины и возвращает готовые выходные отсчёты,
 * [finish] дописывает хвост (нулевое дополнение). Результат идентичен [Resampler.resample] и не зависит от разбиения на блоки.
 * Память -- O(длина фильтра), поэтому декодер может ресемплировать «на лету», не держа запись в исходной частоте.
 */
class ResamplerStream(private val inRate: Int, private val outRate: Int) {
    private val up: Int
    private val down: Int
    private val half: Int
    private val taps: Int
    private val table: Array<FloatArray>

    private var buf = FloatArray(0)
    private var bufLen = 0
    private var bufStart = 0L // абсолютный индекс входного отсчёта buf[0]
    private var totalIn = 0L
    private var nextOut = 0L

    init {
        require(inRate > 0 && outRate > 0) { "Некорректная частота: $inRate -> $outRate" }
        val g = gcd(inRate, outRate)
        up = outRate / g // L
        down = inRate / g // M
        val ratio = outRate.toDouble() / inRate
        // cutoff (циклов на входной отсчёт): при понижении -- ниже nyquist выхода, иначе ниже nyquist входа
        val fc = 0.5 * min(1.0, ratio) * 0.96
        val halfLen = ZERO_CROSSINGS / (2 * fc) // в входных отсчётах
        half = floor(halfLen).toInt() + 1
        taps = 2 * half
        // Таблица фильтров по фазам: фаза p соответствует дробному сдвигу frac = p / up
        table = Array(up) { p ->
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
    }

    fun push(input: FloatArray, length: Int = input.size): FloatArray {
        if (inRate == outRate) return input.copyOf(length)
        if (length > 0) {
            if (bufLen + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + length))
            System.arraycopy(input, 0, buf, bufLen, length)
            bufLen += length
            totalIn += length
        }
        return drain(final = false)
    }

    fun finish(): FloatArray = if (inRate == outRate) FloatArray(0) else drain(final = true)

    private fun drain(final: Boolean): FloatArray {
        val maxOutTotal = totalIn * up / down
        var out = FloatArray(1024)
        var n = 0
        while (true) {
            if (final && nextOut >= maxOutTotal) break
            val pos = nextOut * down
            val base = pos / up
            if (!final && base + half >= totalIn) break
            val filter = table[(pos % up).toInt()]
            val start = base - half + 1
            var acc = 0f
            if (start >= bufStart && start + taps <= totalIn) {
                val off = (start - bufStart).toInt()
                for (k in 0 until taps) acc += filter[k] * buf[off + k]
            } else {
                for (k in 0 until taps) {
                    val idx = start + k
                    if (idx >= bufStart && idx < totalIn) acc += filter[k] * buf[(idx - bufStart).toInt()]
                }
            }
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = acc
            nextOut++
        }
        // выбрасываем входные отсчёты, которые больше не понадобятся
        val keepFrom = maxOf(bufStart, nextOut * down / up - half + 1)
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart = keepFrom
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private companion object {
        const val KAISER_BETA = 8.0
        const val ZERO_CROSSINGS = 16

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun sinc(x: Double): Double = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

        /** Окно Кайзера на |x| <= 1 (вне -- 0). */
        fun kaiser(x: Double): Double {
            if (abs(x) >= 1.0) return 0.0
            return bessel0(KAISER_BETA * sqrt(1 - x * x)) / bessel0(KAISER_BETA)
        }

        fun bessel0(x: Double): Double {
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
}
