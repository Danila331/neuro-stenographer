package ru.neurosteno.app.models.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Живой прогресс загрузок (воркер пишет, экран «Модели» читает) -- общий для процесса, потому что воркер запускается
 * в том же процессе приложения. Скорость сглаживается по выборкам не чаще раза в 1,5 с.
 */
object ModelDownloadProgress {
    data class Item(
        val doneBytes: Long,
        val totalBytes: Long,
        val file: String,
        val speedBps: Long = 0,
        val sampleAtMs: Long = 0,
        val sampleBytes: Long = 0,
    )

    private val _state = MutableStateFlow<Map<String, Item>>(emptyMap())
    val state: StateFlow<Map<String, Item>> = _state.asStateFlow()

    fun publish(setId: String, done: Long, total: Long, file: String, nowMs: Long = System.currentTimeMillis()) {
        val prev = _state.value[setId]
        val next = if (prev == null) {
            Item(done, total, file, 0, nowMs, done)
        } else {
            val dt = nowMs - prev.sampleAtMs
            if (dt >= 1_500) {
                val inst = (done - prev.sampleBytes).coerceAtLeast(0) * 1000 / dt
                val speed = if (prev.speedBps == 0L) inst else (prev.speedBps * 0.6 + inst * 0.4).toLong()
                Item(done, total, file, speed, nowMs, done)
            } else {
                prev.copy(doneBytes = done, totalBytes = total, file = file)
            }
        }
        _state.value = _state.value + (setId to next)
    }

    fun clear(setId: String) {
        _state.value = _state.value - setId
    }
}
