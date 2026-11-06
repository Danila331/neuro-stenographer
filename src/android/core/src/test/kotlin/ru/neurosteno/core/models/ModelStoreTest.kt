package ru.neurosteno.core.models

import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ModelStoreTest {
    private fun makeSet(dir: File, id: String, files: Map<String, String>, withHash: Boolean = true): DirectoryModelSource {
        val set = File(dir, "src-$id").apply { mkdirs() }
        files.forEach { (p, c) -> File(set, p).apply { parentFile.mkdirs() }.writeText(c) }
        val entries = files.map { (p, c) ->
            ModelFileEntry(p, c.toByteArray().size.toLong(), if (withHash) ModelStore.sha256(File(set, p)) else null)
        }
        File(set, "manifest.json").writeText(ModelManifest(id, "medium", "1", true, entries).toJson())
        return DirectoryModelSource(set)
    }

    private val files = mapOf("asr/model.int8.onnx" to "A".repeat(1000), "asr/tokens.txt" to "б", "vad/silero.onnx" to "vad!")

    @Test
    fun `install is complete, becomes active and resolves config paths`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 1L shl 30 })
        val seen = ArrayList<Long>()
        store.install(makeSet(tmp, "medium", files), verifyHashes = true) { done, _ -> seen += done }
        assertEquals(SetState.COMPLETE, store.state("medium"))
        assertEquals("medium", store.activeSetId)
        assertTrue(store.verify("medium"))
        assertEquals("б", store.resolve("asr/tokens.txt")!!.readText())
        assertNull(store.resolve("asr/nope.onnx"))
        assertEquals(listOf("llm/q.gguf"), store.missing(listOf("asr/tokens.txt", "llm/q.gguf")))
        assertTrue(seen.isNotEmpty() && seen.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(files.values.sumOf { it.toByteArray().size.toLong() }, seen.last())
        assertFalse(File(tmp, "models/.tmp-medium").exists())
    }

    @Test
    fun `delete then restore from the same source`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 1L shl 30 })
        val src = makeSet(tmp, "medium", files)
        store.install(src)
        assertTrue(store.delete("medium"))
        assertEquals(SetState.MISSING, store.state("medium"))
        assertNull(store.activeSetId)
        assertNull(store.activeDir())
        assertEquals(files.keys.toList(), store.missing(files.keys.toList())) // всё missing, без исключений
        store.install(src)
        assertEquals(SetState.COMPLETE, store.state("medium"))
        assertEquals("medium", store.activeSetId)
    }

    @Test
    fun `second set does not steal active and deleting active switches to another complete set`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 1L shl 30 })
        store.install(makeSet(tmp, "lite", mapOf("a.txt" to "x")))
        store.install(makeSet(tmp, "hard", mapOf("b.txt" to "y")))
        assertEquals("lite", store.activeSetId)
        assertEquals(listOf("hard", "lite"), store.listSets().map { it.id })
        store.delete("lite")
        assertEquals("hard", store.activeSetId)
    }

    @Test
    fun `not enough space fails before touching the disk`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 10 }, spaceMarginBytes = 0)
        assertThrows<InsufficientSpaceException> { store.install(makeSet(tmp, "medium", files)) }
        assertEquals(SetState.MISSING, store.state("medium"))
        assertFalse(File(tmp, "models/.tmp-medium").exists())
    }

    @Test
    fun `corrupted source leaves no partial set and keeps the old one`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 1L shl 30 })
        store.install(makeSet(tmp, "medium", mapOf("a.txt" to "old")))
        val bad = makeSet(tmp, "medium2", mapOf("a.txt" to "new!!"))
        // портим файл источника после выпуска манифеста: размер не совпадёт
        File(tmp, "src-medium2/a.txt").writeText("sho")
        val renamed = object : ModelSource by bad {
            override fun manifest() = bad.manifest().copy(id = "medium")
        }
        assertThrows<IOException> { store.install(renamed) }
        assertEquals("old", store.resolve("a.txt")!!.readText())
        assertFalse(File(tmp, "models/.tmp-medium").exists())
    }

    @Test
    fun `hash mismatch is detected only when verification requested`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 1L shl 30 })
        val src = makeSet(tmp, "x", mapOf("a.txt" to "data"))
        File(tmp, "src-x/a.txt").writeText("DATA") // тот же размер, другое содержимое
        assertThrows<IOException> { store.install(src, verifyHashes = true) }
        store.install(src, verifyHashes = false)
        assertFalse(store.verify("x"))
    }

    @Test
    fun `incomplete set is not active`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 1L shl 30 })
        store.install(makeSet(tmp, "medium", files))
        File(tmp, "models/medium/asr/tokens.txt").delete()
        assertEquals(SetState.INCOMPLETE, store.state("medium"))
        assertNull(store.activeDir())
    }

    @Test
    fun `path traversal and bad ids are rejected`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "models"), usableSpace = { 1L shl 30 })
        assertThrows<IllegalArgumentException> { store.setDir("../evil") }
        assertThrows<IllegalArgumentException> { store.setDir(".hidden") }
        val src = object : ModelSource {
            override fun manifest() = ModelManifest("evil", "x", files = listOf(ModelFileEntry("../../pwn", 1)))
            override fun open(relativePath: String) = "x".byteInputStream()
        }
        assertThrows<IllegalArgumentException> { store.install(src) }
        assertFalse(File(tmp, "pwn").exists())
    }

    @Test
    fun `missing manifest or empty store never throws`(@TempDir tmp: File) {
        val store = ModelStore(File(tmp, "nothing"), usableSpace = { 1L shl 30 })
        assertTrue(store.listSets().isEmpty())
        assertNull(store.activeDir())
        assertNull(store.resolve("a"))
        assertEquals(SetState.MISSING, store.state("zzz"))
    }
}
