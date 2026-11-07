package ru.neurosteno.app.search

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.neurosteno.core.search.EmbedderConfig
import ru.neurosteno.core.search.TextEmbedder
import ru.neurosteno.embed.OrtTextEmbedder

/**
 * Держит эмбеддер (ONNX Runtime) загруженным, пока им пользуются, и выгружает через [idleMs] простоя либо по [release]:
 * эмбеддер, LLM и ASR никогда не лежат в памяти одновременно (бюджет 1.5 ГБ). [get] возвращает null, если модели нет, она не
 * загрузилась или сейчас занята память ([canLoad] = false, например идёт обработка записи) -- вызывающий тогда работает без векторов.
 */
class EmbedderHolder(
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val canLoad: () -> Boolean,
    /** Каталог активного набора моделей и конфиг эмбеддера; null -- нет набора/поиск выключен. */
    private val source: () -> Pair<File, EmbedderConfig>?,
) {
    private var embedder: OrtTextEmbedder? = null
    private var key: String? = null
    private val lastUse = AtomicLong(0)
    private var releaseJob: Job? = null
    private val lock = Any()
    @Volatile var lastLoadMs: Long = 0
        private set

    private val facade = object : TextEmbedder {
        override val dim: Int get() = synchronized(lock) { embedder?.dim ?: 0 }
        override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
            val e = synchronized(lock) { embedder } ?: throw IllegalStateException("эмбеддер выгружен")
            try {
                return e.embed(texts, isQuery)
            } finally {
                touch()
            }
        }
    }

    fun get(): TextEmbedder? {
        if (!canLoad()) return null
        val (dir, cfg) = source() ?: return null
        synchronized(lock) {
            val k = "${dir.absolutePath}|${cfg.model}|${cfg.tokenizer}|${cfg.pooling}|${cfg.queryPrefix}|${cfg.docPrefix}"
            if (embedder != null && key != k) unloadLocked()
            if (embedder == null) {
                val t0 = System.nanoTime()
                embedder = OrtTextEmbedder.createOrNull(dir, cfg) { Log.e(TAG, "эмбеддер не загрузился", it) }
                key = k
                lastLoadMs = (System.nanoTime() - t0) / 1_000_000
                if (embedder != null) Log.i(TAG, "embedder loaded model=${cfg.model} dim=${cfg.dim} threads=${cfg.threads} ms=$lastLoadMs")
            }
            if (embedder == null) return null
        }
        touch()
        return facade
    }

    private fun touch() {
        lastUse.set(System.nanoTime())
        synchronized(lock) {
            if (releaseJob?.isActive == true) return
            releaseJob = scope.launch {
                while (true) {
                    delay(idleMs)
                    if ((System.nanoTime() - lastUse.get()) / 1_000_000 >= idleMs) {
                        release()
                        return@launch
                    }
                }
            }
        }
    }

    fun release() = synchronized(lock) {
        releaseJob?.cancel()
        releaseJob = null
        unloadLocked()
    }

    private fun unloadLocked() {
        embedder?.let {
            runCatching { it.close() }
            Log.i(TAG, "embedder released")
        }
        embedder = null
        key = null
    }

    val isLoaded: Boolean get() = synchronized(lock) { embedder != null }

    private companion object {
        const val TAG = "Neurosteno"
        const val IDLE_MS = 20_000L
    }
}
