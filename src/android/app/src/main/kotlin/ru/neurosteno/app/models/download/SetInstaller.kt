package ru.neurosteno.app.models.download

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import ru.neurosteno.app.models.Catalog
import ru.neurosteno.app.models.CatalogFile
import ru.neurosteno.app.models.CatalogSet
import ru.neurosteno.core.models.InsufficientSpaceException
import ru.neurosteno.core.models.ModelFileEntry
import ru.neurosteno.core.models.ModelManifest
import ru.neurosteno.core.models.ModelStore

/** Прогресс загрузки набора: скачано/всего байт и текущий файл. */
fun interface DownloadProgress {
    fun onProgress(doneBytes: Long, totalBytes: Long, currentFile: String)
}

/**
 * Скачивание и установка набора из [Catalog]: файлы в `models/.dl-<id>/` (докачка между запусками), каждый файл проверяется
 * по размеру и sha256 (маркер `<файл>.ok` -- повторно не проверяется), архивы распаковываются и удаляются, в конце
 * записывается `manifest.json` и каталог атомарно переименовывается в `models/<id>`.
 */
class SetInstaller(
    private val store: ModelStore,
    private val catalog: Catalog,
    private val downloader: FileDownloader = FileDownloader(),
    private val usableSpace: () -> Long = { store.root.apply { mkdirs() }.usableSpace },
    private val marginBytes: Long = 64L * 1024 * 1024,
) {
    fun stagingDir(setId: String): File = File(store.root, ".dl-$setId")

    /** Сколько байт набора уже лежит в промежуточном каталоге (для надписи «Загружено X, можно продолжить»). */
    fun partialBytes(setId: String): Long =
        stagingDir(setId).takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && !it.name.endsWith(OK_SUFFIX) }?.sumOf { it.length() } ?: 0L

    fun discardPartial(setId: String) {
        stagingDir(setId).deleteRecursively()
    }

    /** Блокирующая загрузка. Бросает [InsufficientSpaceException], [DownloadCancelledException], [IOException]. */
    fun install(set: CatalogSet, isCancelled: () -> Boolean, progress: DownloadProgress): ModelManifest {
        val problems = catalog.problems(requireHttps = false).filter { it.startsWith("${set.id}/") }
        if (problems.isNotEmpty()) throw IOException("Каталог моделей повреждён: ${problems.first()}")
        val staging = stagingDir(set.id).apply { mkdirs() }
        val total = catalog.downloadBytes(set)
        // нужное место: ещё не скачанное + распаковка архивов; уже лежащее в staging учитывается самим томом
        val remaining = (total - partialBytes(set.id)).coerceAtLeast(0) + catalog.installedBytes(set).let { installed ->
            set.files.filter { it.archive != null }.sumOf { it.size }.coerceAtMost(installed)
        }
        val free = usableSpace()
        if (free < remaining + marginBytes) throw InsufficientSpaceException(remaining + marginBytes, free)

        var done = 0L
        fun report(extra: Long, name: String) = progress.onProgress((done + extra).coerceAtMost(total), total, name)
        report(0, "")

        // 1. файлы по прямым ссылкам
        for (f in set.files.filter { it.url != null }) {
            if (isCancelled()) throw DownloadCancelledException()
            val target = File(staging, f.path)
            if (!isVerified(target, f)) {
                downloader.download(f.url!!, target, f.size, isCancelled) { report(it, f.path) }
                verifyAndMark(target, f) { report(f.size, it) }
            }
            done += f.size
            report(0, f.path)
        }
        // 2. архивы
        for (archiveId in set.files.mapNotNull { it.archive }.distinct()) {
            val members = set.files.filter { it.archive == archiveId }
            val a = catalog.archive(archiveId) ?: throw IOException("Нет архива $archiveId в каталоге")
            if (members.any { !isVerified(File(staging, it.path), it) }) {
                val archiveFile = File(staging, ".archive-$archiveId")
                downloader.download(a.url, archiveFile, a.size, isCancelled) { report(it, archiveId) }
                if (a.sha256 != null && !ModelStore.sha256(archiveFile).equals(a.sha256, ignoreCase = true)) {
                    archiveFile.delete()
                    throw DownloadIntegrityException("Контрольная сумма архива $archiveId не совпала")
                }
                report(a.size, "#Распаковка архива")
                extract(a.stripComponents, archiveFile, staging, members, isCancelled)
                archiveFile.delete()
            }
            done += a.size
            report(0, archiveId)
        }
        // 3. манифест и атомарная установка
        val manifest = ModelManifest(
            id = set.id, tier = set.tier, version = set.version, builtin = false,
            files = set.files.map { ModelFileEntry(it.path, it.size, it.sha256) },
        )
        File(staging, ModelManifest.FILE_NAME).writeText(manifest.toJson(), Charsets.UTF_8)
        staging.walkTopDown().filter { it.isFile && it.name.endsWith(OK_SUFFIX) }.forEach { it.delete() }
        val dst = store.setDir(set.id)
        dst.deleteRecursively()
        if (!staging.renameTo(dst)) throw IOException("Не удалось установить набор ${set.id}")
        if (store.activeSetId == null || store.activeDir() == null) store.activeSetId = set.id
        progress.onProgress(total, total, "")
        return manifest
    }

    private fun isVerified(file: File, f: CatalogFile): Boolean =
        file.isFile && file.length() == f.size && File(file.path + OK_SUFFIX).isFile

    private fun verifyAndMark(file: File, f: CatalogFile, report: ((String) -> Unit)? = null) {
        report?.invoke("#Проверка контрольной суммы")
        if (file.length() != f.size) {
            file.delete()
            throw DownloadIntegrityException("Размер ${f.path}: ожидалось ${f.size}, получено ${file.length()}")
        }
        if (f.sha256 != null && !ModelStore.sha256(file).equals(f.sha256, ignoreCase = true)) {
            file.delete()
            throw DownloadIntegrityException("Контрольная сумма ${f.path} не совпала")
        }
        File(file.path + OK_SUFFIX).writeText("ok")
    }

    private fun extract(strip: Int, archive: File, staging: File, members: List<CatalogFile>, isCancelled: () -> Boolean) {
        val wanted = members.associateBy { it.member!! }
        val found = HashSet<String>()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                if (isCancelled()) throw DownloadCancelledException()
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.removePrefix("./").split('/').drop(strip).joinToString("/")
                val f = wanted[name] ?: continue
                val target = File(staging, f.path)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val n = tar.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                verifyAndMark(target, f)
                found += name
            }
        }
        val absent = wanted.keys - found
        if (absent.isNotEmpty()) throw DownloadIntegrityException("В архиве нет файлов: ${absent.joinToString()}")
    }

    private companion object {
        const val OK_SUFFIX = ".ok"
    }
}
