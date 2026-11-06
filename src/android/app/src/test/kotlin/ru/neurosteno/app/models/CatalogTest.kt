package ru.neurosteno.app.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.core.TierConfig

/** Проверка поставляемого каталога наборов (`assets/model_sets.json`) на согласованность с конфигами уровней в config/tiers. */
class CatalogTest {
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "config/tiers").isDirectory }
    private val catalog = Catalog.parse(File(repoRoot, "src/android/app/src/main/assets/model_sets.json").readText())

    @Test
    fun catalogIsConsistent() {
        assertEquals(emptyList<String>(), catalog.problems())
        assertEquals(setOf("lite", "medium", "hard"), catalog.sets.map { it.id }.toSet())
    }

    @Test
    fun urlsAreOfficialHosts() {
        val hosts = catalog.sets.flatMap { it.files }.mapNotNull { it.url }.plus(catalog.archives.map { it.url })
            .map { java.net.URI(it).host }.toSet()
        assertTrue(hosts.toString(), hosts.all { it == "huggingface.co" || it == "github.com" })
        val repos = catalog.sets.flatMap { it.files }.mapNotNull { it.url }.filter { it.contains("huggingface.co") }
            .map { it.substringAfter("huggingface.co/").substringBefore("/", "").let { owner -> owner } }.toSet()
        assertTrue(repos.toString(), repos.all { it == "csukuangfj" || it == "ggml-org" })
    }

    @Test
    fun everySetCoversItsTierConfig() {
        for (set in catalog.sets) {
            val cfg = TierConfig.parse(File(repoRoot, "config/tiers/${set.tier}.json").readText())
            val missing = cfg.requiredModelPaths().filter { req -> set.files.none { it.path == req || it.path.startsWith("$req/") } }
            assertEquals("набор ${set.id}: нет файлов для $missing", emptyList<String>(), missing)
        }
    }

    @Test
    fun everyFileHasChecksum() {
        for (f in catalog.sets.flatMap { it.files }) assertTrue("${f.path}: нет sha256", !f.sha256.isNullOrBlank())
    }

    @Test
    fun downloadSizesAreSane() {
        val medium = catalog.set("medium")!!
        assertTrue(catalog.downloadBytes(medium) in 1_500_000_000L..2_000_000_000L)
        val lite = catalog.set("lite")!!
        assertTrue(catalog.downloadBytes(lite) < catalog.downloadBytes(medium))
        assertTrue(catalog.downloadBytes(catalog.set("hard")!!) > catalog.downloadBytes(medium))
    }
}
