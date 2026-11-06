package ru.neurosteno.app.models

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Каталог наборов моделей для загрузки -- файл `assets/model_sets.json` (генерирует `app/tools/gen_model_sets.py` из
 * `scripts/fetch_models.py`; URL -- только официальные источники: релизы sherpa-onnx, Hugging Face csukuangfj/ggml-org).
 *
 * Файл набора: либо прямой `url`, либо `member` внутри архива (`archive` -- id из [Catalog.archives]; так публикуется,
 * например, английская модель Parakeet). `path` -- куда файл ложится внутри набора (тот же относительный путь,
 * что в `config/tiers/<tier>.json`).
 */
@Serializable
data class CatalogFile(
    val path: String,
    val size: Long,
    val sha256: String? = null,
    val url: String? = null,
    val archive: String? = null,
    val member: String? = null,
)

@Serializable
data class CatalogArchive(
    val id: String,
    val url: String,
    val size: Long,
    val format: String = "tar.bz2",
    val sha256: String? = null,
    /** Сколько ведущих компонентов пути отрезать у имён внутри архива (как `tar --strip-components`). */
    val stripComponents: Int = 0,
)

@Serializable
data class CatalogSet(
    val id: String,
    val tier: String,
    val title: String,
    val description: String = "",
    val recommended: Boolean = false,
    val version: String = "1",
    val files: List<CatalogFile>,
)

@Serializable
data class Catalog(
    val version: Int = 1,
    val archives: List<CatalogArchive> = emptyList(),
    val sets: List<CatalogSet> = emptyList(),
) {
    fun set(id: String): CatalogSet? = sets.firstOrNull { it.id == id }
    fun archive(id: String): CatalogArchive? = archives.firstOrNull { it.id == id }

    /** Сколько байт нужно скачать для набора (прямые файлы + архивы, из которых берутся файлы набора). */
    fun downloadBytes(set: CatalogSet): Long {
        val direct = set.files.filter { it.url != null }.sumOf { it.size }
        val archiveIds = set.files.mapNotNull { it.archive }.distinct()
        return direct + archiveIds.sumOf { archive(it)?.size ?: 0L }
    }

    /** Размер набора на диске после установки. */
    fun installedBytes(set: CatalogSet): Long = set.files.sumOf { it.size }

    /** Проверка согласованности каталога: у каждого файла есть источник, архивы существуют, пути безопасны. */
    fun problems(requireHttps: Boolean = true): List<String> = buildList {
        for (s in sets) {
            for (f in s.files) {
                val where = "${s.id}/${f.path}"
                if ((f.url == null) == (f.archive == null)) add("$where: нужно ровно одно из url/archive")
                if (f.archive != null && (archive(f.archive) == null || f.member == null)) add("$where: архив ${f.archive} не найден или нет member")
                if (requireHttps && f.url != null && !f.url.startsWith("https://")) add("$where: URL не https")
                if (f.path.startsWith("/") || f.path.split('/').any { it == ".." }) add("$where: небезопасный путь")
                if (f.size <= 0) add("$where: размер не задан")
            }
        }
        for (a in archives) if (requireHttps && !a.url.startsWith("https://")) add("архив ${a.id}: URL не https")
    }

    companion object {
        const val ASSET = "model_sets.json"
        /** Только для debug-сборок: файл рядом с результатами подменяет каталог (проверка загрузки на малых файлах). */
        const val OVERRIDE_FILE = "model_sets_override.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Catalog = json.decodeFromString(serializer(), text)

        /** Каталог приложения; битый/отсутствующий файл -- пустой каталог (экран «Модели» покажет только установленное). */
        fun load(context: Context, allowOverride: Boolean): Catalog {
            if (allowOverride) {
                val f = File(context.getExternalFilesDir(null), OVERRIDE_FILE)
                if (f.isFile) runCatching { return parse(f.readText(Charsets.UTF_8)) }
            }
            return runCatching {
                parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
            }.getOrDefault(Catalog())
        }
    }
}
