package ru.neurosteno.core.models

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Набор моделей = каталог `models/<set-id>/` + `manifest.json` (ARCHITECTURE «Менеджер моделей»).
 * Пути моделей из `config/tiers/<tier>.json` разрешаются относительно каталога АКТИВНОГО набора.
 */
@Serializable
data class ModelFileEntry(val path: String, val size: Long, val sha256: String? = null)

@Serializable
data class ModelManifest(
    val id: String,
    val tier: String,
    /** Версия набора (не приложения). */
    val version: String = "1",
    /** Встроенный в APK набор (его можно удалить и восстановить из assets). */
    val builtin: Boolean = false,
    val files: List<ModelFileEntry> = emptyList(),
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FILE_NAME = "manifest.json"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        fun parse(text: String): ModelManifest = json.decodeFromString(serializer(), text)
    }
}

/** Источник файлов набора: assets APK, каталог, позже -- загрузчик. */
interface ModelSource {
    /** Манифест набора. */
    fun manifest(): ModelManifest
    fun open(relativePath: String): InputStream
}

/** Каталог на диске как источник (для тестов и для staging на ПК). */
class DirectoryModelSource(private val dir: File) : ModelSource {
    override fun manifest(): ModelManifest = ModelManifest.parse(File(dir, ModelManifest.FILE_NAME).readText())
    override fun open(relativePath: String): InputStream = File(dir, relativePath).inputStream()
}

class InsufficientSpaceException(val neededBytes: Long, val availableBytes: Long) :
    IOException("Недостаточно места: нужно $neededBytes байт, доступно $availableBytes")

enum class SetState { MISSING, INCOMPLETE, COMPLETE }

/**
 * Хранилище наборов в `root` (Android: `filesDir/models`). Установка атомарна: копирование в `.tmp-<id>`,
 * проверка размеров (и sha256, если [verifyHashes]) и только потом rename. Удаление и повторная установка
 * («удалить встроенный набор / восстановить из assets») -- те же [delete] и [install].
 */
class ModelStore(
    val root: File,
    /** Сколько байт свободно на томе (для тестов подменяется). */
    private val usableSpace: () -> Long = { root.apply { mkdirs() }.usableSpace },
    /** Запас свободного места сверх размера набора. */
    private val spaceMarginBytes: Long = 64L * 1024 * 1024,
) {
    private val activeFile get() = File(root, "active.txt")

    fun setDir(id: String): File = File(root, safeId(id))

    fun listSets(): List<ModelManifest> =
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { readManifest(it) }.sortedBy { it.id }

    fun readManifest(dir: File): ModelManifest? = runCatching {
        ModelManifest.parse(File(dir, ModelManifest.FILE_NAME).readText())
    }.getOrNull()

    /** Быстрая проверка: все файлы на месте и размеры совпадают. */
    fun state(id: String): SetState {
        val dir = setDir(id)
        if (!dir.isDirectory) return SetState.MISSING
        val m = readManifest(dir) ?: return SetState.INCOMPLETE
        val ok = m.files.all { e -> File(dir, e.path).let { it.isFile && it.length() == e.size } }
        return if (ok) SetState.COMPLETE else SetState.INCOMPLETE
    }

    /** Глубокая проверка sha256 (долго, вызывать по требованию). */
    fun verify(id: String): Boolean {
        val dir = setDir(id)
        val m = readManifest(dir) ?: return false
        return m.files.all { e ->
            val f = File(dir, e.path)
            f.isFile && f.length() == e.size && (e.sha256 == null || sha256(f).equals(e.sha256, ignoreCase = true))
        }
    }

    var activeSetId: String?
        get() = runCatching { activeFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        set(value) {
            root.mkdirs()
            if (value == null) activeFile.delete() else activeFile.writeText(value)
        }

    /** Активный набор, если он целиком на диске; иначе null (приложение показывает понятное сообщение). */
    fun activeDir(): File? = activeSetId?.takeIf { state(it) == SetState.COMPLETE }?.let(::setDir)

    /** Файл модели по относительному пути из конфига в активном наборе; null, если набора/файла нет. */
    fun resolve(relativePath: String): File? = activeDir()?.let { File(it, relativePath) }?.takeIf { it.exists() }

    /** Пути из [required], которых нет в активном наборе. */
    fun missing(required: List<String>): List<String> {
        val dir = activeDir() ?: return required
        return required.filter { !File(dir, it).exists() }
    }

    /**
     * Копирует набор из [source] в `root/<id>`. [progress] получает (скопировано, всего) байт.
     * Бросает [InsufficientSpaceException] до начала копирования, ничего не оставляя на диске.
     */
    fun install(source: ModelSource, verifyHashes: Boolean = false, progress: (Long, Long) -> Unit = { _, _ -> }): ModelManifest {
        val manifest = source.manifest()
        val id = safeId(manifest.id)
        val total = manifest.totalBytes
        val existing = setDir(id).takeIf { it.isDirectory }?.let { dirSize(it) } ?: 0L
        val free = usableSpace() + existing // старый набор будет заменён
        if (free < total + spaceMarginBytes) throw InsufficientSpaceException(total + spaceMarginBytes, free)

        root.mkdirs()
        val tmp = File(root, ".tmp-$id")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            var copied = 0L
            val buf = ByteArray(1 shl 20)
            for (e in manifest.files) {
                val target = File(tmp, safeRelative(e.path))
                target.parentFile?.mkdirs()
                val md = if (verifyHashes && e.sha256 != null) MessageDigest.getInstance("SHA-256") else null
                var written = 0L
                source.open(e.path).use { input ->
                    target.outputStream().use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md?.update(buf, 0, n)
                            written += n
                            copied += n
                            progress(copied, total)
                        }
                    }
                }
                if (written != e.size) throw IOException("Размер ${e.path}: ожидалось ${e.size}, получено $written")
                if (md != null && !hex(md.digest()).equals(e.sha256, ignoreCase = true)) {
                    throw IOException("Контрольная сумма ${e.path} не совпала")
                }
            }
            File(tmp, ModelManifest.FILE_NAME).writeText(manifest.toJson())
            val dst = setDir(id)
            dst.deleteRecursively()
            if (!tmp.renameTo(dst)) throw IOException("Не удалось переименовать $tmp -> $dst")
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            throw e
        }
        if (activeSetId == null) activeSetId = id
        return manifest
    }

    /** Удаляет набор. Если он был активным -- активным становится любой другой полный набор либо ничего. */
    fun delete(id: String): Boolean {
        val removed = setDir(id).deleteRecursively()
        if (activeSetId == id) activeSetId = listSets().firstOrNull { state(it.id) == SetState.COMPLETE }?.id
        return removed
    }

    fun usedBytes(id: String): Long = setDir(id).takeIf { it.isDirectory }?.let(::dirSize) ?: 0L

    private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun safeId(id: String): String {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } && !id.startsWith(".")) {
            "Недопустимый id набора: '$id'"
        }
        return id
    }

    private fun safeRelative(p: String): String {
        require(!p.startsWith("/") && p.split('/').none { it == ".." }) { "Недопустимый путь в манифесте: '$p'" }
        return p
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val b = ByteArray(1 shl 20)
                while (true) {
                    val n = i.read(b)
                    if (n < 0) break
                    md.update(b, 0, n)
                }
            }
            return hex(md.digest())
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
