package ru.neurosteno.core.fragment

import kotlin.math.sqrt
import ru.neurosteno.core.TimeSpan

/**
 * Сопоставление говорящих фрагмента (локальные, из диаризации только отрезка) с глобальными (уже есть в расшифровке).
 *
 * 1. **Эмбеддинги** (основной способ): косинусная близость локального эмбеддинга (речь говорящего в фрагменте) и
 *    эталонного эмбеддинга глобального говорящего (его реплики ВНЕ заменяемого диапазона). Пары с близостью
 *    `>= threshold` назначаются жадно по убыванию близости, строго один-к-одному (два разных локальных говорящих
 *    не могут стать одним глобальным -- диаризация фрагмента сочла их разными).
 * 2. **Пересечение по времени** (запасной способ -- нет эмбеддингов, фрагмент слишком короткий, у глобального нет
 *    реплик вне диапазона): локальный говорящий получает глобального, чьи СТАРЫЕ реплики внутри заменяемого диапазона
 *    пересекаются с его речью не менее чем на `minOverlapFraction` от его длительности. Если у обоих есть эмбеддинги
 *    и они ниже порога, по времени не сопоставляем: эмбеддинги важнее (старая разметка могла быть неверной).
 * 3. Остальные -- **новые** говорящие (`globalIndex == null`).
 */
class SpeakerMatcher(
    private val threshold: Double = DEFAULT_THRESHOLD,
    private val minOverlapFraction: Double = 0.3,
) {
    class Local(val id: Int, val embedding: FloatArray?, val turns: List<TimeSpan>)

    /** [replacedSpans] -- отрезки старых реплик этого говорящего внутри заменяемого диапазона. */
    class Global(val index: Int, val embedding: FloatArray?, val replacedSpans: List<TimeSpan>)

    enum class Method { EMBEDDING, TIME_OVERLAP, NEW }

    /** [globalIndex] == null -- новый говорящий. [score] -- косинусная близость (EMBEDDING) либо доля пересечения (TIME_OVERLAP). */
    data class Match(val localId: Int, val globalIndex: Int?, val method: Method, val score: Double?)

    fun match(locals: List<Local>, globals: List<Global>): List<Match> {
        val result = LinkedHashMap<Int, Match>()
        val takenGlobals = HashSet<Int>()

        // 1) эмбеддинги
        val pairs = ArrayList<Triple<Local, Global, Double>>()
        for (l in locals) for (g in globals) {
            val le = l.embedding ?: continue
            val ge = g.embedding ?: continue
            val c = cosine(le, ge)
            if (c >= threshold) pairs += Triple(l, g, c)
        }
        for ((l, g, c) in pairs.sortedByDescending { it.third }) {
            if (l.id in result || g.index in takenGlobals) continue
            result[l.id] = Match(l.id, g.index, Method.EMBEDDING, c)
            takenGlobals += g.index
        }

        // 2) время
        for (l in locals.sortedByDescending { total(it.turns) }) {
            if (l.id in result) continue
            val own = total(l.turns)
            var best: Global? = null
            var bestOv = 0L
            for (g in globals) {
                if (g.index in takenGlobals) continue
                if (l.embedding != null && g.embedding != null) continue // эмбеддинги уже сказали «не он»
                val ov = overlap(l.turns, g.replacedSpans)
                if (ov > bestOv) {
                    best = g
                    bestOv = ov
                }
            }
            if (best != null && own > 0 && bestOv >= minOverlapFraction * own) {
                result[l.id] = Match(l.id, best.index, Method.TIME_OVERLAP, bestOv.toDouble() / own)
                takenGlobals += best.index
            }
        }

        // 3) новые
        for (l in locals) if (l.id !in result) result[l.id] = Match(l.id, null, Method.NEW, null)
        return locals.map { result.getValue(it.id) }
    }

    companion object {
        /**
         * Порог косинусной близости. Подобран на `ru-dialog-tts-cats` для эмбеддингов medium (см. README, раздел
         * «Повторная обработка фрагмента»): для TTS-голосов «тот же говорящий» -- обычно > 0.6, «другой» -- < 0.4.
         */
        const val DEFAULT_THRESHOLD = 0.5

        fun cosine(a: FloatArray, b: FloatArray): Double {
            val n = minOf(a.size, b.size)
            if (n == 0) return 0.0
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in 0 until n) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            if (na <= 0 || nb <= 0) return 0.0
            return dot / (sqrt(na) * sqrt(nb))
        }

        fun total(spans: List<TimeSpan>): Long = spans.sumOf { maxOf(0L, it.endMs - it.startMs) }

        fun overlap(a: List<TimeSpan>, b: List<TimeSpan>): Long {
            var sum = 0L
            for (x in a) for (y in b) sum += maxOf(0L, minOf(x.endMs, y.endMs) - maxOf(x.startMs, y.startMs))
            return sum
        }
    }
}
