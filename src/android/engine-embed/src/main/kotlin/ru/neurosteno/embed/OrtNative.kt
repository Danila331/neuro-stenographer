package ru.neurosteno.embed

import java.io.File

/**
 * JNI над C API ONNX Runtime (`embed_jni.cpp`). Библиотека ORT -- та же `libonnxruntime.so`, что несёт sherpa-onnx (одна копия на
 * приложение); на хосте (тесты) пути к обеим библиотекам задаёт [loadHost].
 */
internal object OrtNative {
    @Volatile private var loaded = false

    /** Android: загружает ORT из sherpa-onnx и JNI-обёртку. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("onnxruntime")
        System.loadLibrary("neurosteno_embed")
        loaded = true
    }

    /** Хост (macOS/Linux): [jni] -- собранная `libneurosteno_embed` (scripts/build_host_embed_jni.sh), [ort] -- libonnxruntime (из sherpa-onnx). */
    @Synchronized
    fun loadHost(jni: File, ort: File) {
        if (loaded) return
        System.load(jni.absolutePath)
        setLibraryPath(ort.absolutePath)
        loaded = true
    }

    external fun setLibraryPath(path: String)
    external fun version(): String
    external fun create(model: String, threads: Int): Long
    external fun inputNames(handle: Long): Array<String>
    external fun run(handle: Long, ids: LongArray, mask: LongArray, types: LongArray?, batch: Int, seq: Int, out: FloatArray): Int
    external fun destroy(handle: Long)
}
