package ru.neurosteno.llm

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import ru.neurosteno.core.LlmAssistant

/**
 * [LlmAssistant] поверх llama.cpp (GGUF, CPU). Загружает модель в конструкторе; `close()` освобождает нативную память.
 *
 * Ускорение (GPU и т.п.) -- ТОЛЬКО опционально: при [gpuLayers] > 0 сначала пробуем с ним, при любой неудаче
 * (нет бэкенда, мало памяти, ошибка драйвера) загружаем на CPU; в сборке по умолчанию GPU-бэкендов нет вообще.
 * Отмена: `Thread.interrupt()` (так делает `runInterruptible` оркестратора) -- нативный цикл проверяет флаг на
 * каждом токене и на каждом куске prefill.
 *
 * Перед созданием вызовите [availability]: если вернулось не null -- LLM недоступна на этом устройстве, пунктуацию
 * нужно пропустить (процесс не должен упасть на SIGILL из-за неподдерживаемых инструкций).
 */
class LlamaAssistant(
    modelFile: File,
    nCtx: Int = 2048,
    nThreads: Int = 4,
    gpuLayers: Int = 0,
) : LlmAssistant {

    private var handle: Long = 0
    private val lock = Any()

    /** Режим, в котором реально загружена модель (для диагностики/статистики). */
    val loadedWithGpuLayers: Int

    init {
        availability()?.let { throw IOException("LLM недоступна: $it") }
        if (!modelFile.isFile) throw IOException("Нет файла модели: ${modelFile.path}")
        var layers = gpuLayers
        var h = if (layers > 0) LlamaNative.load(modelFile.absolutePath, nCtx, nThreads, layers) else 0L
        if (h == 0L) {
            layers = 0
            h = LlamaNative.load(modelFile.absolutePath, nCtx, nThreads, 0)
        }
        if (h == 0L) throw IOException("Не удалось загрузить LLM: ${LlamaNative.lastError()}")
        handle = h
        loadedWithGpuLayers = layers
    }

    override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String = synchronized(lock) {
        check(handle != 0L) { "LlamaAssistant закрыт" }
        val cb = TokenCallback { bytes ->
            onToken(String(bytes, Charsets.UTF_8))
            !Thread.currentThread().isInterrupted
        }
        val out = LlamaNative.generate(handle, prompt, maxTokens, cb)
        if (out == null) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("LLM прервана")
            throw IOException("Ошибка генерации: ${LlamaNative.lastError()}")
        }
        String(out, Charsets.UTF_8)
    }

    /** Мгновенно просит остановить текущую генерацию (из другого потока). */
    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                LlamaNative.free(handle)
                handle = 0
            }
        }
    }

    companion object {
        /** null -- можно пользоваться; иначе причина недоступности (на русском, для лога/предупреждения). */
        fun availability(): String? {
            try {
                LlamaNative.systemInfo()
            } catch (e: UnsatisfiedLinkError) {
                return "нативная библиотека не загружена (${e.message})"
            } catch (e: LinkageError) {
                return "нативная библиотека не загружена (${e.message})"
            }
            return cpuProblem()
        }

        /** Сборка для Android использует dotprod (armv8.2-a+dotprod); без него процесс упал бы на SIGILL. */
        private fun cpuProblem(): String? {
            val isAndroid = System.getProperty("java.vm.vendor")?.contains("Android", ignoreCase = true) == true ||
                System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true
            if (!isAndroid) return null
            val info = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return null
            return if (info.contains("asimddp")) null else "процессор без dotprod (asimddp)"
        }
    }
}
