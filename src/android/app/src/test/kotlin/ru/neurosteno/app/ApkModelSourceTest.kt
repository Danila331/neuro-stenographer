package ru.neurosteno.app

import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.neurosteno.app.models.ApkModelSource
import ru.neurosteno.core.models.ModelFileEntry
import ru.neurosteno.core.models.ModelManifest
import ru.neurosteno.core.models.ModelStore

/** Чтение встроенного набора прямо из APK (zip) -- замена AssetManager, чтобы не мапить APK в процесс (B44). */
class ApkModelSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun apk(vararg files: Pair<String, ByteArray>): File {
        val f = tmp.newFile("app.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, data) in files) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun readsManifestAndFilesAndInstallsIntoStore() {
        val model = ByteArray(3_000_000) { (it % 251).toByte() }
        val manifest = ModelManifest("lite", "lite", files = listOf(ModelFileEntry("asr/m.onnx", model.size.toLong()), ModelFileEntry("t.txt", 3)))
        val file = apk(
            "assets/models/lite/manifest.json" to manifest.toJson().toByteArray(),
            "assets/models/lite/asr/m.onnx" to model,
            "assets/models/lite/t.txt" to "abc".toByteArray(),
        )
        assertTrue(ApkModelSource.available(file, "lite"))
        assertFalse(ApkModelSource.available(file, "medium"))
        val src = ApkModelSource(file, "lite")
        assertEquals(manifest, src.manifest())
        val store = ModelStore(tmp.newFolder("models"), usableSpace = { 1L shl 30 })
        store.install(src)
        assertEquals(model.toList(), File(store.setDir("lite"), "asr/m.onnx").readBytes().toList())
        assertEquals("abc", File(store.setDir("lite"), "t.txt").readText())
    }

    @Test(expected = FileNotFoundException::class)
    fun missingEntryIsReportedAndZipIsClosed() {
        ApkModelSource(apk("assets/models/lite/manifest.json" to "{}".toByteArray()), "lite").open("nope.bin")
    }

    @Test
    fun notAnApkIsNotAvailable() {
        assertFalse(ApkModelSource.available(tmp.newFile("x.apk").apply { writeText("not a zip") }, "lite"))
    }
}
