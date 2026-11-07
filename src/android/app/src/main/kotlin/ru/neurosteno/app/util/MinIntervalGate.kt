package ru.neurosteno.app.util

/**
 * Пропускает событие не чаще раза в [minIntervalMs] (троттлинг обновлений уведомления: Android отбрасывает обновления
 * чаще ~5 в секунду, а оркестратор шлёт прогресс из нескольких потоков). Потокобезопасен.
 */
class MinIntervalGate(
    private val minIntervalMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var last = Long.MIN_VALUE

    /** true -- действие можно выполнять сейчас (и интервал начинается заново); [force] пропускает всегда. */
    @Synchronized
    fun tryPass(force: Boolean = false): Boolean {
        val t = nowMs()
        if (force || last == Long.MIN_VALUE || t - last >= minIntervalMs) {
            last = t
            return true
        }
        return false
    }
}
