package ru.neurosteno.app.licenses

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Реестр сторонних компонентов (`assets/third_party_notices.json`): полнота, лицензии, файлы текстов, версии из каталога Gradle и models.json. */
class NoticesTest {
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "config/tiers").isDirectory }
    private val assets = File(repoRoot, "src/android/app/src/main/assets")
    private val notices: List<Notice> = Notices.parse(File(assets, "third_party_notices.json").readText(Charsets.UTF_8))

    @Test
    fun everyNoticeHasLicenseAndExistingNonEmptyTextFile() {
        assertTrue(notices.size >= 30)
        assertEquals("id уникальны", notices.size, notices.map { it.id }.toSet().size)
        for (n in notices) {
            assertTrue("${n.id}: пустая лицензия", n.license.isNotBlank())
            assertTrue("${n.id}: пустое имя", n.name.isNotBlank())
            assertTrue("${n.id}: неизвестный вид ${n.kind}", n.kind in setOf(Notice.MODEL, Notice.LIBRARY, Notice.FONT))
            val f = File(assets, "licenses/${n.licenseFile}")
            assertTrue("${n.id}: нет файла лицензии ${n.licenseFile}", f.isFile)
            assertTrue("${n.id}: пустой текст лицензии ${n.licenseFile}", f.readText(Charsets.UTF_8).trim().length > 300)
            assertTrue("${n.id}: нет источника", n.source.startsWith("https://"))
            assertTrue("${n.id}: нет назначения", n.purpose.isNotBlank())
        }
    }

    @Test
    fun fullTextFilesAreRealLicenses() {
        fun text(f: String) = File(assets, "licenses/$f").readText(Charsets.UTF_8)
        assertTrue(text("Apache-2.0.txt").contains("Apache License") && text("Apache-2.0.txt").contains("Version 2.0, January 2004") && text("Apache-2.0.txt").contains("END OF TERMS AND CONDITIONS"))
        for (f in listOf("MIT.txt", "MIT-GigaAM.txt", "MIT-pyannote.txt", "MIT-llama.cpp.txt", "MIT-onnxruntime.txt")) {
            assertTrue(f, text(f).startsWith("MIT License") && text(f).contains("Permission is hereby granted, free of charge") && text(f).contains("THE SOFTWARE IS PROVIDED \"AS IS\""))
        }
        for (f in listOf("OFL-unbounded.txt", "OFL-golostext.txt", "OFL-jetbrainsmono.txt")) assertTrue(f, text(f).contains("SIL OPEN FONT LICENSE Version 1.1"))
        // справки без полного юридического текста помечены в реестре и называют ссылку на полный текст
        val linkOnly = notices.filter { !it.textComplete }
        assertEquals(setOf("objectbox-native"), linkOnly.map { it.id }.toSet())
        for (n in linkOnly) assertTrue(n.id, text(n.licenseFile).contains("https://") && n.note.isNotBlank())
    }

    @Test
    fun everyShippedModelOfModelsJsonIsListed() {
        val models = Json.parseToJsonElement(File(repoRoot, "config/models.json").readText(Charsets.UTF_8)).jsonObject["models"]!!.jsonArray
        val shipped = models.map { it.jsonObject }.filter { m -> m["tiers"]!!.jsonArray.any { it.jsonPrimitive.content in setOf("lite", "medium", "hard") } }
        assertTrue(shipped.isNotEmpty())
        val ids = notices.filter { it.kind == Notice.MODEL }.map { it.id }
        for (m in shipped) {
            val path = m["path"]!!.jsonPrimitive.content
            assertTrue("модель $path не описана в реестре", ids.any { path == it || path.startsWith("$it/") })
        }
        // лицензия в реестре согласована с models.json (первое слово: MIT / Apache-2.0 / CC-BY-4.0)
        for (m in shipped) {
            val path = m["path"]!!.jsonPrimitive.content
            val n = notices.first { it.kind == Notice.MODEL && (path == it.id || path.startsWith(it.id + "/")) }
            assertEquals(path, m["license"]!!.jsonPrimitive.content.substringBefore(' '), n.license)
        }
    }

    @Test
    fun requiredComponentsAreListedWithRightLicenses() {
        fun n(id: String) = notices.first { it.id == id }
        assertEquals("Apache-2.0", n("objectbox-api").license)
        assertTrue(n("objectbox-native").license.contains("бинарная") && !n("objectbox-native").textComplete)
        assertTrue(n("objectbox-api").note.contains("только при сборке"))
        assertEquals("MIT", n("onnxruntime-lib").license)
        assertEquals("MIT", n("onnxruntime-headers").license)
        assertEquals("Apache-2.0", n("embed/user2-small").license)
        assertEquals("MIT", n("embed/rubert-tiny-turbo").license)
        assertEquals("Apache-2.0", n("asr/streaming-zipformer-small-ru-int8").license)
        assertTrue(notices.count { it.id.startsWith("diar/emb-") && it.license == "Apache-2.0" } == 3) // 3D-Speaker: lite, medium, hard
        for (id in listOf("font/unbounded", "font/golos-text", "font/jetbrains-mono")) assertEquals("OFL-1.1", n(id).license)
        for (id in listOf("androidx-work", "commons-compress", "sherpa-onnx", "llama.cpp", "kotlinx-coroutines")) assertTrue(id, notices.any { it.id == id })
    }

    @Test
    fun versionsMatchGradleCatalog() {
        val toml = File(repoRoot, "src/android/gradle/libs.versions.toml").readText(Charsets.UTF_8)
        fun v(key: String) = Regex("^" + Regex.escape(key) + "\\s*=\\s*\"([^\"]+)\"", RegexOption.MULTILINE).find(toml)!!.groupValues[1]
        fun n(id: String) = notices.first { it.id == id }
        assertEquals(v("objectbox"), n("objectbox-api").version)
        assertEquals(v("objectbox"), n("objectbox-native").version)
        assertEquals(v("sherpa-onnx"), n("sherpa-onnx").version)
        assertEquals(v("work"), n("androidx-work").version)
        assertEquals(v("commons-compress"), n("commons-compress").version)
        assertEquals(v("coroutines"), n("kotlinx-coroutines").version)
        assertEquals(v("serialization"), n("kotlinx-serialization-json").version)
        assertEquals(v("core-ktx"), n("androidx-core-ktx").version)
        assertEquals(v("activity-compose"), n("androidx-activity-compose").version)
        assertEquals(v("lifecycle"), n("androidx-lifecycle").version)
        assertTrue(n("compose").version.startsWith(v("compose-bom")))
        assertTrue(n("llama.cpp").version.startsWith(v("llama-cpp-tag")))
        assertEquals(v("kotlin"), n("kotlin-stdlib").version)
    }

    @Test
    fun bundledFontsMatchRegistry() {
        val fonts = File(repoRoot, "src/android/app/src/main/res/font").listFiles()!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(setOf("unbounded", "golos_text", "jetbrains_mono"), fonts)
        assertEquals(3, notices.count { it.kind == Notice.FONT })
    }

    @Test
    fun docsLicensesListsTheSameComponents() {
        val doc = File(repoRoot, "docs/LICENSES.md").readText(Charsets.UTF_8).lowercase()
        for (n in notices.filter { it.kind != Notice.FONT }) {
            // ключи поиска: id, первый сегмент пути, имя без скобок и «точечные» префиксы id (androidx-core-ktx -> androidx.core)
            val dotted = n.id.replace('-', '.').split('.')
            val keys = listOf(n.id, n.id.substringBefore('/'), n.name.substringBefore(" (")) + (2..dotted.size).map { dotted.take(it).joinToString(".") } + n.id.replace('-', '.')
            assertTrue("в docs/LICENSES.md нет компонента ${n.id}", keys.any { doc.contains(it.lowercase()) })
        }
        for (f in listOf("Unbounded", "Golos Text", "JetBrains Mono")) assertTrue(f, doc.contains(f.lowercase()))
    }
}
