package ru.neurosteno.app.ui

/**
 * Оценка оставшегося времени обработки. Основа -- экстраполяция по общему прогрессу (прогресс оркестратора взвешен по
 * стадиям, поэтому достаточно ровный); пока прогресса мало, берётся оценка по RTF прошлых запусков и длительности записи.
 */
object EtaEstimator {
    /** Прогресс, с которого экстраполяции можно верить. */
    private const val MIN_PROGRESS = 0.08f

    /**
     * @param elapsedMs сколько уже идёт обработка
     * @param overall общий прогресс 0..1
     * @param audioDurationMs длительность записи, если известна
     * @param priorRtf средний RTF прошлых запусков этого набора моделей (0 -- нет данных)
     * @return миллисекунды до конца или null, если оценить пока нечем
     */
    fun remainingMs(elapsedMs: Long, overall: Float, audioDurationMs: Long?, priorRtf: Double): Long? {
        if (overall >= 1f) return 0
        val byProgress = if (overall >= MIN_PROGRESS && elapsedMs > 0) (elapsedMs * (1.0 - overall) / overall).toLong() else null
        val byRtf = if (audioDurationMs != null && audioDurationMs > 0 && priorRtf > 0) {
            maxOf(0L, (audioDurationMs * priorRtf).toLong() - elapsedMs)
        } else null
        return when {
            byProgress != null && byRtf != null -> {
                // по мере продвижения вес экстраполяции растёт: 0.08 -> 30 %, 0.5 и дальше -> 100 %
                val w = ((overall - MIN_PROGRESS) / (0.5f - MIN_PROGRESS)).coerceIn(0f, 1f) * 0.7 + 0.3
                (byProgress * w + byRtf * (1 - w)).toLong()
            }
            byProgress != null -> byProgress
            else -> byRtf
        }
    }
}
