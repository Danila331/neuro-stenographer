package ru.neurosteno.core.asr

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.SampleSpan
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.VadRescueConfig

/** Результат планирования речи: интервалы речи (мс, после паддинга) и чанки для ASR/LID (мс). */
class SpeechPlan(val speech: List<TimeSpan>, val chunks: List<TimeSpan>)

/**
 * Упаковка речевых сегментов VAD в чанки -- порт `src/eval/refpipe/refpipe/vad.py` (режим `vad.chunking = pack`,
 * исходно `bench_asr/vad.py`). Все индексы -- ОТСЧЁТЫ 16 кГц (мс получаются только в самом конце, `a * 1000 / 16000`,
 * как `_span` в эталоне). Проверяется JSON-кейсами, сгенерированными из refpipe (`scripts/gen_engine_cases.py`).
 */
object VadChunking {
    private const val SR = 16_000

    /** `speech_segments` + `chunks_from_segments` эталона. [raw] -- сырые сегменты Silero в отсчётах. */
    fun plan(
        x: FloatArray,
        raw: List<SampleSpan>,
        padMs: Int,
        rescue: VadRescueConfig,
        chunkMaxS: Double,
        chunkMinS: Double,
    ): SpeechPlan {
        var segs = raw.map { it.start to it.end }
        if (rescue.enabled) {
            segs = (segs + rescueSegments(x, segs, rescue)).sortedWith(compareBy({ it.first }, { it.second }))
        }
        val padded = padSegments(segs, padMs, x.size)
        val chunks = packChunks(padded, chunkMaxS, chunkMinS, x)
        return SpeechPlan(
            speech = padded.filter { it.second > it.first }.map(::span),
            chunks = chunks.filter { it.second > it.first }.map(::span),
        )
    }

    private fun span(p: Pair<Int, Int>) = TimeSpan(p.first.toLong() * 1000 / SR, p.second.toLong() * 1000 / SR)

    /** Паддинг каждого сегмента на [padMs]; граница с соседом -- середина паузы, сегменты НЕ склеиваются. */
    fun padSegments(segs: List<Pair<Int, Int>>, padMs: Int, nSamples: Int): List<Pair<Int, Int>> {
        val pad = (padMs.toLong() * SR / 1000).toInt()
        val out = ArrayList<Pair<Int, Int>>(segs.size)
        for (i in segs.indices) {
            val (a, b) = segs[i]
            var lo = max(0, a - pad)
            var hi = min(nSamples, b + pad)
            if (i > 0) lo = max(lo, Math.floorDiv(segs[i - 1].second + a, 2))
            if (i + 1 < segs.size) hi = min(hi, Math.floorDiv(b + segs[i + 1].first, 2))
            out += lo to hi
        }
        return out
    }

    /** Точка резки в `[a + 0.55*mx, a + mx]` с минимумом энергии (окна 30 мс, сглаживание 3 окна, как `np.convolve(..., "same")`). */
    internal fun quietCut(x: FloatArray, a: Int, b: Int, mx: Int): Int {
        val lo = a + (0.55 * mx).toInt()
        val hi = min(b, a + mx)
        if (hi <= lo) return hi
        val w = (0.03 * SR).toInt()
        val n = (hi - lo) / w
        if (n < 4) return hi
        val e = DoubleArray(n) { k ->
            var s = 0.0
            for (i in lo + k * w until lo + (k + 1) * w) s += x[i].toDouble() * x[i]
            sqrt(s / w)
        }
        var bestIdx = 0
        var best = Double.MAX_VALUE
        for (k in 0 until n) {
            val sm = ((if (k > 0) e[k - 1] else 0.0) + e[k] + (if (k + 1 < n) e[k + 1] else 0.0)) / 3.0
            if (sm < best) {
                best = sm
                bestIdx = k
            }
        }
        return lo + bestIdx * w + w / 2
    }

    /** `pack_chunks` эталона. */
    fun packChunks(segs: List<Pair<Int, Int>>, maxS: Double, minS: Double, x: FloatArray): List<Pair<Int, Int>> {
        if (segs.isEmpty()) return emptyList()
        val mx = (maxS * SR).toInt()
        val pieces = ArrayList<IntArray>()
        for ((a0, b) in segs) {
            var a = a0
            while (b - a > mx) {
                val c = quietCut(x, a, b, mx)
                pieces += intArrayOf(a, c)
                a = c
            }
            pieces += intArrayOf(a, b)
        }
        val chunks = ArrayList<IntArray>()
        chunks += pieces[0].copyOf()
        for (k in 1 until pieces.size) {
            val (a, b) = pieces[k][0] to pieces[k][1]
            if (b - chunks.last()[0] <= mx) chunks.last()[1] = b else chunks += intArrayOf(a, b)
        }
        val merged = ArrayList<IntArray>()
        for (c in chunks) {
            val (a, b) = c[0] to c[1]
            if (merged.isNotEmpty() && (b - a) < minS * SR && (b - merged.last()[0]) <= mx * 1.2) merged.last()[1] = b
            else merged += intArrayOf(a, b)
        }
        return merged.map { it[0] to it[1] }
    }

    /**
     * `rescue_segments` эталона: энергетические кандидаты речи там, где Silero ничего не нашёл. Кадры 20 мс по RMS (дБ);
     * порог = max(10-й перцентиль + snr_db, abs_floor_db); паузы активности короче bridge_ms склеиваются; куски >= min_ms,
     * не пересекающиеся с [covered] (+-margin_ms), возвращаются (отсчёты).
     */
    fun rescueSegments(x: FloatArray, covered: List<Pair<Int, Int>>, cfg: VadRescueConfig): List<Pair<Int, Int>> {
        val fr = (0.02 * SR).toInt()
        val n = x.size / fr
        if (n < 10) return emptyList()
        val db = DoubleArray(n) { k ->
            var s = 0.0
            for (i in k * fr until (k + 1) * fr) s += x[i].toDouble() * x[i]
            20.0 * log10(max(sqrt(s / fr), 1e-9))
        }
        val thr = max(percentile(db, 10.0) + cfg.snrDb, cfg.absFloorDb)
        val act = BooleanArray(n) { db[it] > thr }
        val bridge = cfg.bridgeMs / 20
        val minf = cfg.minMs / 20
        var i = 0
        while (i < n) {
            if (!act[i]) {
                var j = i
                while (j < n && !act[j]) j++
                if (i in 1 until n && j < n && j - i <= bridge) for (k in i until j) act[k] = true
                i = j
            } else {
                i++
            }
        }
        val cov = BooleanArray(n)
        val m = cfg.marginMs / 20
        for ((a, b) in covered) {
            val from = max(0, a / fr - m)
            val to = min(n, b / fr + m + 1)
            for (k in from until to) cov[k] = true
        }
        val out = ArrayList<Pair<Int, Int>>()
        i = 0
        while (i < n) {
            if (act[i] && !cov[i]) {
                var j = i
                while (j < n && act[j] && !cov[j]) j++
                if (j - i >= minf) out += (i * fr) to (j * fr)
                i = j
            } else {
                i++
            }
        }
        return out
    }

    /** numpy.percentile (линейная интерполяция). */
    internal fun percentile(v: DoubleArray, p: Double): Double {
        val s = v.sortedArray()
        val idx = (s.size - 1) * p / 100.0
        val lo = idx.toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (idx - lo)
    }

    /** Удобная обёртка для оркестратора. */
    fun plan(audio: AudioBuffer, raw: List<SampleSpan>, padMs: Int, rescue: VadRescueConfig, chunkMaxS: Double, chunkMinS: Double): SpeechPlan =
        plan(audio.samples, raw, padMs, rescue, chunkMaxS, chunkMinS)

}
