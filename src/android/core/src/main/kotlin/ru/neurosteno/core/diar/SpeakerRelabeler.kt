package ru.neurosteno.core.diar

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import ru.neurosteno.core.RelabelConfig
import ru.neurosteno.core.SpeakerEmbedder
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.Word

/**
 * «Говорящие по ASR-фразам» (BACKLOG B46) -- порт 1:1 эталона `src/eval/bench_diar/bench_diar/spkphrase.py` (numpy -> Double).
 * Шаги: юниты из слов (пауза <= gap_ms, длина <= max_unit_ms, резка по смене доминирующего turn'а) -> эмбеддинг на юнит
 * (pad_ms, центральное окно embed_max_s, нули до 0.3 с, L2) -> надёжные юниты -> ward (Lance-Williams) -> число говорящих по
 * силуэту (1-cos, k in [k_min, k_max], при равенстве меньший k) -> центроиды с весом длительности для ВСЕХ юнитов + refine.
 * Выход -- turns юнитов, которые заменяют turns диаризатора перед Aligner. Проверяется JSON-кейсами из эталона
 * (`scripts/gen_diar_cases.py`).
 */
object SpeakerRelabeler {
    private const val SR = 16_000

    /** Юнит: [startMs, endMs) и индексы слов [w0, w1). */
    data class Unit(val startMs: Long, val endMs: Long, val w0: Int, val w1: Int)

    // ---- 1. юниты ----
    fun makeUnits(words: List<Pair<Long, Long>>, turns: List<SpeakerTurn>?, p: RelabelConfig): List<Unit> {
        val n = words.size
        var runs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            var j = i + 1
            while (j < n && words[j].first - words[j - 1].second <= p.gapMs && words[j].second - words[i].first <= p.maxUnitMs) j++
            runs += intArrayOf(i, j)
            i = j
        }
        if (!turns.isNullOrEmpty()) {
            val res = ArrayList<IntArray>()
            for ((a, b) in runs.map { it[0] to it[1] }) {
                val dom = (a until b).map { dominant(words[it], turns) }
                val cuts = ArrayList<Int>()
                var start = 0
                for (k in 1 until b - a) {
                    val d0 = dom[k - 1]
                    val d1 = dom[k]
                    if (d0 != null && d1 != null && d0 != d1) {
                        val left = words[a + k - 1].second - words[a + start].first
                        val right = words[b - 1].second - words[a + k].first
                        if (left >= p.splitMinSideMs && right >= p.splitMinSideMs) {
                            cuts += k
                            start = k
                        }
                    }
                }
                var prev = 0
                for (k in cuts + listOf(b - a)) {
                    res += intArrayOf(a + prev, a + k)
                    prev = k
                }
            }
            runs = res
        }
        return runs.map { Unit(words[it[0]].first, words[it[1] - 1].second, it[0], it[1]) }
    }

    private fun dominant(w: Pair<Long, Long>, turns: List<SpeakerTurn>): Int? {
        var best: Int? = null
        var bo = 0L
        for (t in turns) { // при равенстве побеждает первый (нужно строго большее пересечение)
            val o = min(w.second, t.endMs) - max(w.first, t.startMs)
            if (o > bo) {
                best = t.speaker
                bo = o
            }
        }
        return best
    }

    // ---- 2. эмбеддинги ----
    fun embedUnits(
        audio: FloatArray,
        units: List<Unit>,
        embed: SpeakerEmbedder,
        p: RelabelConfig,
        progress: ((Float) -> kotlin.Unit)? = null,
    ): Array<DoubleArray> {
        val minLen = (0.3 * SR).toInt()
        val rows = ArrayList<DoubleArray>(units.size)
        for ((ui, u) in units.withIndex()) {
            progress?.invoke(ui.toFloat() / units.size)
            var a = max(0L, (u.startMs - p.padMs) * SR / 1000)
            var b = min(audio.size.toLong(), (u.endMs + p.padMs) * SR / 1000)
            if ((b - a).toDouble() > p.embedMaxS * SR) {
                val mid = (a + b) / 2
                val half = (p.embedMaxS * SR).toInt() / 2
                a = mid - half
                b = mid + half
            }
            val from = a.coerceIn(0, audio.size.toLong()).toInt()
            val to = b.coerceIn(from.toLong(), audio.size.toLong()).toInt()
            var seg = audio.copyOfRange(from, to)
            if (seg.size < minLen) seg = seg.copyOf(minLen)
            rows += embed.embed(seg).let { e -> DoubleArray(e.size) { e[it].toDouble() } }
        }
        return Array(rows.size) { k ->
            val r = rows[k]
            var s = 0.0
            for (v in r) s += v * v
            val norm = sqrt(s) + 1e-9
            DoubleArray(r.size) { r[it] / norm }
        }
    }

    // ---- 4. ward ----
    /** Lance-Williams ward на квадратах евклидовых расстояний; при равенстве -- наименьший плоский индекс (i<j). */
    fun wardMerges(y: Array<DoubleArray>): List<Pair<Int, Int>> {
        val n = y.size
        val inf = Double.POSITIVE_INFINITY
        val d = Array(n) { DoubleArray(n) { inf } }
        for (i in 0 until n) for (j in i + 1 until n) {
            var s = 0.0
            for (k in y[i].indices) {
                val t = y[i][k] - y[j][k]
                s += t * t
            }
            d[i][j] = s
        }
        val ids = IntArray(n) { it }
        val size = DoubleArray(n) { 1.0 }
        val active = BooleanArray(n) { true }
        val merges = ArrayList<Pair<Int, Int>>()
        for (t in 0 until n - 1) {
            var bi = 0
            var bj = 0
            var best = inf
            var found = false
            for (i in 0 until n) for (j in 0 until n) {
                val v = d[i][j]
                if (!found || v < best) { // np.argmin: первый минимальный (в том числе среди одних inf)
                    best = v
                    bi = i
                    bj = j
                    found = true
                }
            }
            val i = bi
            val j = bj
            val dij = d[i][j]
            val ni = size[i]
            val nj = size[j]
            for (m in 0 until n) {
                if (!active[m] || m == i || m == j) continue
                val dim = d[min(i, m)][max(i, m)]
                val djm = d[min(j, m)][max(j, m)]
                val nm = size[m]
                d[min(i, m)][max(i, m)] = ((ni + nm) * dim + (nj + nm) * djm - nm * dij) / (ni + nj + nm)
            }
            for (k in 0 until n) {
                d[j][k] = inf
                d[k][j] = inf
            }
            active[j] = false
            size[i] = ni + nj
            merges += ids[i] to ids[j]
            ids[i] = n + t
        }
        return merges
    }

    /** Метки 0..k-1 (по порядку первого появления) после n-k слияний. */
    fun cut(n: Int, merges: List<Pair<Int, Int>>, k: Int): IntArray {
        val parent = IntArray(2 * n - 1) { it }
        fun find(x0: Int): Int {
            var x = x0
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (t in 0 until n - k) {
            val (a, b) = merges[t]
            parent[find(a)] = n + t
            parent[find(b)] = n + t
        }
        val roots = LinkedHashMap<Int, Int>()
        return IntArray(n) { i -> roots.getOrPut(find(i)) { roots.size } }
    }

    private fun cosineDistances(y: Array<DoubleArray>): Array<DoubleArray> =
        Array(y.size) { i -> DoubleArray(y.size) { j -> 1.0 - dot(y[i], y[j]) } }

    fun silhouette(y: Array<DoubleArray>, lab: IntArray): Double = silhouette(y, lab, cosineDistances(y))

    private fun silhouette(y: Array<DoubleArray>, lab: IntArray, distances: Array<DoubleArray>): Double {
        val ks = lab.toSortedSet().toList()
        if (ks.size < 2) return -1.0
        val n = y.size
        var total = 0.0
        for (i in 0 until n) {
            var sameCnt = 0
            var sameSum = 0.0
            for (j in 0 until n) if (lab[j] == lab[i]) { sameCnt++; sameSum += distances[i][j] }
            if (sameCnt <= 1) continue
            val a = sameSum / (sameCnt - 1)
            var b = Double.POSITIVE_INFINITY
            for (q in ks) {
                if (q == lab[i]) continue
                var c = 0
                var s = 0.0
                for (j in 0 until n) if (lab[j] == q) { c++; s += distances[i][j] }
                b = min(b, s / c)
            }
            total += (b - a) / max(max(a, b), 1e-9)
        }
        return total / n
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (k in a.indices) s += a[k] * b[k]
        return s
    }

    // ---- 3-6. разметка ----
    fun labelUnits(e: Array<DoubleArray>, durMs: LongArray, p: RelabelConfig, stockSingle: Boolean = false): IntArray {
        val n = e.size
        if (n == 0) return IntArray(0)
        var ok = BooleanArray(n) { durMs[it] >= p.minReliableMs }
        if (ok.count { it } < 3) ok = BooleanArray(n) { true }
        var idx = (0 until n).filter { ok[it] }
        if (idx.size > p.maxClusterUnits) {
            idx = idx.sortedWith(compareBy { -durMs[it] }).take(p.maxClusterUnits).sorted() // стабильная сортировка по убыванию длительности
        }
        if (idx.size < 3) return IntArray(n)
        val y = Array(idx.size) { e[idx[it]] }
        val merges = wardMerges(y)
        val distances = cosineDistances(y)
        var bestK = max(p.kMin, 2)
        var best = -2.0
        for (k in max(p.kMin, 2)..min(p.kMax, idx.size - 1)) {
            val s = silhouette(y, cut(idx.size, merges, k), distances)
            if (s > best + 1e-12) {
                best = s
                bestK = k
            }
        }
        // защита от монологов: слабая кластерная структура -> один говорящий
        p.k1SilhouetteThr?.let { if (best < it && (stockSingle || !p.k1RequireStockSingle)) return IntArray(n) }
        val lab = cut(idx.size, merges, bestK)
        val w = DoubleArray(n) { durMs[it].toDouble() }
        val dim = e[0].size

        fun normalize(c: Array<DoubleArray>) {
            for (r in c) {
                var s = 0.0
                for (v in r) s += v * v
                val norm = sqrt(s) + 1e-9
                for (k in r.indices) r[k] /= norm
            }
        }

        fun assign(c: Array<DoubleArray>): IntArray = IntArray(n) { i ->
            var bq = 0
            var bv = Double.NEGATIVE_INFINITY
            for (q in c.indices) {
                val v = dot(e[i], c[q])
                if (v > bv) { bv = v; bq = q }
            }
            bq
        }

        var c = Array(bestK) { q ->
            val acc = DoubleArray(dim)
            for (t in idx.indices) if (lab[t] == q) for (k in 0 until dim) acc[k] += e[idx[t]][k] * w[idx[t]]
            acc
        }
        normalize(c)
        var out = assign(c)
        repeat(p.refine) {
            val prev = c
            c = Array(bestK) { q ->
                if ((0 until n).any { out[it] == q }) {
                    val acc = DoubleArray(dim)
                    for (i in 0 until n) if (out[i] == q) for (k in 0 until dim) acc[k] += e[i][k] * w[i]
                    acc
                } else {
                    prev[q].copyOf()
                }
            }
            normalize(c)
            out = assign(c)
        }
        return out
    }

    /** `stock_is_single` эталона: не более одного говорящего штатной диаризации с долей >= [minShare] суммарной речи. */
    fun stockIsSingle(turns: List<SpeakerTurn>?, minShare: Double = 0.10): Boolean {
        val tot = LinkedHashMap<Int, Long>()
        for (t in turns.orEmpty()) tot[t.speaker] = (tot[t.speaker] ?: 0L) + (t.endMs - t.startMs)
        val sum = tot.values.sum()
        return tot.values.count { sum > 0 && it >= minShare * sum } <= 1
    }

    /** `phrase_turns`: слова (абс. мс) + turns диаризатора (только как точки смены) -> turns юнитов. */
    fun phraseTurns(
        words: List<Word>,
        stockTurns: List<SpeakerTurn>?,
        audio: FloatArray,
        embed: SpeakerEmbedder,
        p: RelabelConfig,
        progress: ((Float) -> kotlin.Unit)? = null,
    ): List<SpeakerTurn> {
        val spans = words.map { it.startMs to it.endMs }
        val units = makeUnits(spans, stockTurns, p)
        if (units.isEmpty()) return emptyList()
        val e = embedUnits(audio, units, embed, p, progress)
        val lab = labelUnits(e, LongArray(units.size) { units[it].endMs - units[it].startMs }, p, stockIsSingle(stockTurns, p.k1StockMinShare))
        return units.mapIndexed { i, u -> SpeakerTurn(lab[i], u.startMs, u.endMs) }
    }
}
