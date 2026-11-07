package ru.neurosteno.llm

/** Колбэк токенов: получает UTF-8 байты завершённых символов; `false` -- остановить генерацию. */
fun interface TokenCallback {
    fun onToken(bytes: ByteArray): Boolean
}

/** Тонкая JNI-обёртка (см. src/main/cpp/llm_jni.cpp). Все методы -- статические. */
internal object LlamaNative {
    init {
        System.loadLibrary("neurosteno_llm")
    }

    /** handle или 0 при ошибке (текст -- [lastError]). */
    @JvmStatic external fun load(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int,
        noRepack: Boolean, flashAttn: Boolean, kvCacheType: String, useMmap: Boolean,
    ): Long

    /** Фактически применённые параметры загрузки (`no_repack=1 mmap=1 flash_attn=enabled kv=q8_0 ...`). */
    @JvmStatic external fun options(handle: Long): String

    @JvmStatic external fun free(handle: Long)

    /** Потокобезопасно относительно generate: поднимает флаг прерывания ggml. */
    @JvmStatic external fun cancel(handle: Long)

    /** UTF-8 байты результата либо null при ошибке/прерывании (см. [lastError]). */
    @JvmStatic external fun generate(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback?): ByteArray?

    @JvmStatic external fun lastError(): String

    @JvmStatic external fun systemInfo(): String
}
