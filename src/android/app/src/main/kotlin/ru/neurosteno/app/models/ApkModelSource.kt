package ru.neurosteno.app.models

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile
import ru.neurosteno.core.models.ModelManifest
import ru.neurosteno.core.models.ModelSource

/**
 * Встроенный набор, прочитанный из APK через [ZipFile] (обычные read-вызовы), а не через AssetManager.
 * AssetManager мапит весь APK в память процесса: при копировании 1.7 ГБ моделей пик PSS доходил до 1.26 ГБ
 * (`.apk mmap`, чистые файловые страницы; BACKLOG B44). Здесь страницы остаются в page cache ядра и в PSS процесса не попадают.
 * Записи в APK хранятся без сжатия (`noCompress`), поэтому чтение -- простая потоковая копия.
 */
class ApkModelSource(private val apk: File, private val setId: String) : ModelSource {
    private fun entry(rel: String) = "assets/${AssetModelSource.ROOT}/$setId/$rel"

    override fun manifest(): ModelManifest = ZipFile(apk).use { zf ->
        val e = zf.getEntry(entry(ModelManifest.FILE_NAME)) ?: error("нет манифеста набора $setId в APK")
        ModelManifest.parse(zf.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    override fun open(relativePath: String): InputStream {
        val zf = ZipFile(apk)
        val e = zf.getEntry(entry(relativePath))
        if (e == null) {
            zf.close()
            throw java.io.FileNotFoundException("нет $relativePath в APK")
        }
        // закрытие потока закрывает и ZipFile
        return object : FilterInputStream(zf.getInputStream(e)) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    zf.close()
                }
            }
        }
    }

    companion object {
        /** true, если набор [setId] лежит в APK как записи `assets/models/<setId>/...` (в обычной сборке -- да). */
        fun available(apk: File, setId: String): Boolean = runCatching {
            ZipFile(apk).use { it.getEntry("assets/${AssetModelSource.ROOT}/$setId/${ModelManifest.FILE_NAME}") != null }
        }.getOrDefault(false)
    }
}
