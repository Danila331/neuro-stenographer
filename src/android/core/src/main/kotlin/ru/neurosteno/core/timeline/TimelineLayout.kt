package ru.neurosteno.core.timeline

import kotlin.math.max

/** Реплика для дорожки: говорящий и границы в мс; [added] -- добавлена повторной обработкой фрагмента. */
data class TimelineUtterance(val speakerId: String, val startMs: Long, val endMs: Long, val added: Boolean = false)

/** Отрезок дорожки в пикселях: [x0, x1) по ширине, [lane] -- дорожка по вертикали (перекрывающиеся реплики лежат на разных). */
data class TimelineSegment(
    val speakerId: String,
    val x0: Float,
    val x1: Float,
    val lane: Int,
    val added: Boolean,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Раскладка «дорожки говорящих» (B54): реплики -> отрезки по ширине [widthPx], перекрывающиеся реплики -- на разных
 * параллельных дорожках (жадное разбиение интервалов), не больше [maxLanes] (лишние кладутся на последнюю: пересечения видны,
 * но высота остаётся компактной). Чистая функция без Android -- координаты и время проверяются тестами.
 */
class TimelineLayout(
    utterances: List<TimelineUtterance>,
    val durationMs: Long,
    val widthPx: Float,
    maxLanes: Int = 3,
    /** Минимальная ширина отрезка, чтобы короткая реплика не пропадала. */
    minSegmentPx: Float = 2f,
) {
    private var scaleMs: Long = 1
    val segments: List<TimelineSegment>
    val lanes: Int

    init {
        val total = max(durationMs, utterances.maxOfOrNull { it.endMs } ?: 0L).coerceAtLeast(1L)
        scaleMs = total
        val sorted = utterances.filter { it.endMs > it.startMs }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        val laneEnd = ArrayList<Long>()
        val out = ArrayList<TimelineSegment>(sorted.size)
        for (u in sorted) {
            var lane = laneEnd.indexOfFirst { it <= u.startMs }
            if (lane < 0) {
                if (laneEnd.size < maxLanes) { laneEnd += u.endMs; lane = laneEnd.lastIndex }
                else { lane = laneEnd.lastIndex; laneEnd[lane] = max(laneEnd[lane], u.endMs) }
            } else laneEnd[lane] = u.endMs
            val x0 = msToX(u.startMs, total)
            val x1 = max(msToX(u.endMs, total), x0 + minSegmentPx).coerceAtMost(max(widthPx, x0 + minSegmentPx))
            out += TimelineSegment(u.speakerId, x0, x1, lane, u.added, u.startMs, u.endMs)
        }
        segments = out
        lanes = max(1, laneEnd.size)
    }

    private fun msToX(ms: Long, total: Long) = (ms.coerceIn(0, total).toDouble() / total * widthPx).toFloat()

    /** Время (мс) -> координата x в [0, widthPx]. */
    fun msToX(ms: Long): Float = msToX(ms, scaleMs)

    /** Координата x -> время (мс) в [0, длительность]. */
    fun xToMs(x: Float): Long = (x.coerceIn(0f, widthPx).toDouble() / max(widthPx, 1f) * scaleMs).toLong()

    /** Индекс отрезка под точкой x (на любой дорожке; при пересечении -- самый короткий, он «сверху»), либо -1. */
    fun segmentAt(x: Float): Int {
        var best = -1
        var bestLen = Float.MAX_VALUE
        segments.forEachIndexed { i, s ->
            if (x >= s.x0 && x <= s.x1 && s.x1 - s.x0 < bestLen) { best = i; bestLen = s.x1 - s.x0 }
        }
        return best
    }

    /** Целевая позиция перемотки для касания в точке x: начало реплики, если попали в отрезок, иначе просто время точки. */
    fun seekTargetMs(x: Float): Long = xToMs(x)

    companion object {
        /** Высота дорожки в dp: [lanes] строк по [laneDp] с зазором [gapDp]. */
        fun heightDp(lanes: Int, laneDp: Float = 12f, gapDp: Float = 2f): Float = lanes * laneDp + (lanes - 1) * gapDp
    }
}
