package ru.neurosteno.llm

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import ru.neurosteno.core.LlmAssistant

/**
 * Параметры загрузки llama.cpp из `llm.*` конфига уровня: [noRepack] (`llama_model_params.use_extra_bufts = false`), [flashAttn],
 * [kvCacheType] (`f16` | `q8_0` | `q4_0`; квантованный требует flash attention -- нативный слой включает его сам), [useMmap].
 */
data class LlamaOptions(
    val noRepack: Boolean = false,
    val flashAttn: Boolean = false,
    val kvCacheType: String = "f16",
    val useMmap: Boolean = true,
)

/** Параметры загрузки из секции `llm` конфига уровня. */
fun ru.neurosteno.core.LlmConfig.toLlamaOptions() = LlamaOptions(noRepack, flashAttn, kvCacheType, useMmap)

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
    /** Память и скорость llama.cpp (B48); по умолчанию -- прежнее поведение (repack, f16, без flash attention). */
    options: LlamaOptions = LlamaOptions(),
) : LlmAssistant {

    private var handle: Long = 0
    private val lock = Any()

    /** Режим, в котором реально загружена модель (для диагностики/статистики). */
    val loadedWithGpuLayers: Int

    /** Фактически применённые параметры загрузки (после обходов в нативном слое), для логов и диагностики. */
    val appliedOptions: String

    init {
        availability()?.let { throw IOException("LLM недоступна: $it") }
        if (!modelFile.isFile) throw IOException("Нет файла модели: ${modelFile.path}")
        var layers = gpuLayers
        fun load(gpu: Int) = LlamaNative.load(
            modelFile.absolutePath, nCtx, nThreads, gpu, options.noRepack, options.flashAttn, options.kvCacheType, options.useMmap,
        )
        var h = if (layers > 0) load(layers) else 0L
        if (h == 0L) {
            layers = 0
            h = load(0)
        }
        if (h == 0L) throw IOException("Не удалось загрузить LLM: ${LlamaNative.lastError()}")
        handle = h
        loadedWithGpuLayers = layers
        appliedOptions = LlamaNative.options(h)
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
