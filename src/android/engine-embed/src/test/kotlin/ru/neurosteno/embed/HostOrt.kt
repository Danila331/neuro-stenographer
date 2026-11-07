package ru.neurosteno.embed

import java.io.File

/** Подготовка хоста для тестов: JNI эмбеддера и libonnxruntime из sherpa-onnx (`scripts/build_host_embed_jni.sh`); нет -- тесты пропускаются. */
internal object HostOrt {
    fun dir(): File? {
        val explicit = System.getenv("EMBED_HOST_BUILD")?.let(::File)
        val byRoot = System.getenv("NEUROSTENO_ROOT")?.let { File(it, ".cache/android-deps/embed-host-build") }
        return listOfNotNull(explicit, byRoot).firstOrNull { d -> d.isDirectory && libs(d) != null }
    }

    private fun libs(d: File): Pair<File, File>? {
        val jni = d.listFiles { f -> f.name.startsWith("libneurosteno_embed.") && (f.extension == "dylib" || f.extension == "so") }?.firstOrNull()
        val ort = d.listFiles { f -> f.name.startsWith("libonnxruntime.") && (f.extension == "dylib" || f.extension == "so") }?.firstOrNull()
        return if (jni != null && ort != null) jni to ort else null
    }

    /** true -- библиотеки загружены (или уже были загружены). */
    fun load(): Boolean {
        val d = dir() ?: return false
        val (jni, ort) = libs(d)!!
        OrtNative.loadHost(jni, ort)
        return true
    }
}
