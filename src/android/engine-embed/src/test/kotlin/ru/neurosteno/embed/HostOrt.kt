package ru.neurosteno.embed

import java.io.File
import java.security.MessageDigest

/**
 * Подготовка хоста для тестов: JNI эмбеддера и libonnxruntime из sherpa-onnx (`scripts/build_host_embed_jni.sh`).
 * Библиотека подходит, только если собрана из ТЕКУЩИХ исходников JNI: каталог `embed-host-build-<хэш>` (хэш `embed_jni.cpp` + `CMakeLists.txt`)
 * либо явный `EMBED_HOST_BUILD` с файлом `source.hash`, равным этому хэшу. Нет подходящей -- тесты пропускаются ([skipReason]), а не падают
 * из-за устаревшей сборки.
 */
internal object HostOrt {
    private fun cppDir(): File? {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val c = File(d, "engine-embed/src/main/cpp")
            if (File(c, "embed_jni.cpp").isFile) return c
            d = d.parentFile
        }
        return null
    }

    /** Первые 12 hex SHA-256 от `embed_jni.cpp` + `CMakeLists.txt` -- то же, что считает скрипт сборки. */
    fun sourceHash(): String? {
        val c = cppDir() ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(File(c, "embed_jni.cpp").readBytes())
        md.update(File(c, "CMakeLists.txt").readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun libs(d: File): Pair<File, File>? {
        val jni = d.listFiles { f -> f.name.startsWith("libneurosteno_embed.") && (f.extension == "dylib" || f.extension == "so") }?.firstOrNull()
        val ort = d.listFiles { f -> f.name.startsWith("libonnxruntime.") && (f.extension == "dylib" || f.extension == "so") }?.firstOrNull()
        return if (jni != null && ort != null) jni to ort else null
    }

    private fun candidates(hash: String): List<File> = listOfNotNull(
        System.getenv("EMBED_HOST_BUILD")?.let(::File),
        System.getenv("NEUROSTENO_ROOT")?.let { File(it, ".cache/android-deps/embed-host-build-$hash") },
    ).filter { it.isDirectory && libs(it) != null }

    private fun fresh(hash: String): File? = candidates(hash).firstOrNull { File(it, "source.hash").takeIf { m -> m.isFile }?.readText()?.trim() == hash }

    /** Причина, по которой хостовой JNI нет (для сообщения о пропуске); null -- можно использовать. */
    fun skipReason(): String? {
        val hash = sourceHash() ?: return "не найдены исходники engine-embed/src/main/cpp"
        if (fresh(hash) != null) return null
        val stale = candidates(hash).firstOrNull()
        return if (stale == null) "нет хостовой JNI для исходников $hash: выполните scripts/build_host_embed_jni.sh"
        else "хостовая JNI в $stale собрана из других исходников (нужен хэш $hash): пересоберите scripts/build_host_embed_jni.sh"
    }

    /** true -- библиотеки загружены (или уже были загружены); false -- подходящей сборки нет (тест должен пропуститься). */
    fun load(): Boolean {
        val dir = fresh(sourceHash() ?: return false) ?: return false
        val (jni, ort) = libs(dir)!!
        OrtNative.loadHost(jni, ort)
        return true
    }
}
