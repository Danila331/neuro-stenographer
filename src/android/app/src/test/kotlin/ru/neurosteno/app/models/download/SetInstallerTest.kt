package ru.neurosteno.app.models.download

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.neurosteno.app.models.Catalog
import ru.neurosteno.app.models.CatalogArchive
import ru.neurosteno.app.models.CatalogFile
import ru.neurosteno.app.models.CatalogSet
import ru.neurosteno.core.models.InsufficientSpaceException
import ru.neurosteno.core.models.ModelStore
import ru.neurosteno.core.models.SetState

class SetInstallerTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var root: File
    private lateinit var store: ModelStore
    private val files = HashMap<String, ByteArray>()
    private val rangeRequests = AtomicInteger()
    private val requests = AtomicInteger()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        root = Files.createTempDirectory("models").toFile()
        store = ModelStore(File(root, "models"))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            val data = files[ex.requestURI.path]
            if (data == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                rangeRequests.incrementAndGet()
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                ex.sendResponseHeaders(206, (data.size - from).toLong())
                ex.responseBody.use { it.write(data, from, data.size - from) }
            } else {
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.responseBody.use { it.write(data) }
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun tearDown() { server.stop(0); root.deleteRecursively() }

    private fun payload(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private fun tarBz2(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bos)).use { tar ->
            for ((name, data) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).also { it.size = data.size.toLong() })
                tar.write(data); tar.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun installer(catalog: Catalog, space: () -> Long = { Long.MAX_VALUE / 2 }) =
        SetInstaller(store, catalog, FileDownloader(attempts = 1, sleep = {}), usableSpace = space, marginBytes = 0)

    @Test
    fun installsFilesAndArchiveMembersAtomically() {
        val a = payload(300_000, 1); val b = payload(1_000, 2)
        val archive = tarBz2(mapOf("pkg/enc.onnx" to payload(5_000, 3), "pkg/tokens.txt" to payload(100, 4), "pkg/extra.bin" to payload(7, 5)))
        files["/a.onnx"] = a; files["/b.txt"] = b; files["/pkg.tar.bz2"] = archive
        val catalog = Catalog(
            archives = listOf(CatalogArchive("pkg", "$base/pkg.tar.bz2", archive.size.toLong(), stripComponents = 1)),
            sets = listOf(CatalogSet("t", "medium", "Тест", files = listOf(
                CatalogFile("asr/a.onnx", a.size.toLong(), sha(a), url = "$base/a.onnx"),
                CatalogFile("b.txt", b.size.toLong(), sha(b), url = "$base/b.txt"),
                CatalogFile("asr/en/enc.onnx", 5_000, sha(payload(5_000, 3)), archive = "pkg", member = "enc.onnx"),
                CatalogFile("asr/en/tokens.txt", 100, sha(payload(100, 4)), archive = "pkg", member = "tokens.txt"),
            ))),
        )
        val progress = ArrayList<Long>()
        val manifest = installer(catalog).install(catalog.set("t")!!, { false }) { done, total, _ ->
            progress += done
            assertTrue(done <= total)
        }
        assertEquals("t", manifest.id)
        assertEquals(SetState.COMPLETE, store.state("t"))
        assertTrue(store.verify("t"))
        assertEquals("t", store.activeSetId)
        assertEquals(payload(5_000, 3).toList(), File(store.setDir("t"), "asr/en/enc.onnx").readBytes().toList())
        assertFalse(File(store.setDir("t"), "asr/en/extra.bin").exists())
        assertFalse(File(store.root, ".dl-t").exists())
        assertEquals(progress.sorted(), progress) // прогресс монотонный
        assertEquals(catalog.downloadBytes(catalog.set("t")!!), progress.last())
        assertEquals("medium", store.readManifest(store.setDir("t"))!!.tier)
    }

    @Test
    fun resumesPartialFileWithRangeRequest() {
        val a = payload(200_000, 7)
        files["/a.bin"] = a
        val catalog = Catalog(sets = listOf(CatalogSet("t", "lite", "T", files = listOf(CatalogFile("a.bin", a.size.toLong(), sha(a), url = "$base/a.bin")))))
        val staging = File(store.root, ".dl-t").apply { mkdirs() }
        File(staging, "a.bin.part").writeBytes(a.copyOfRange(0, 50_000))
        val inst = installer(catalog)
        assertEquals(50_000, inst.partialBytes("t"))
        inst.install(catalog.set("t")!!, { false }) { _, _, _ -> }
        assertEquals(1, rangeRequests.get())
        assertEquals(a.toList(), File(store.setDir("t"), "a.bin").readBytes().toList())
    }

    @Test
    fun checksumMismatchFailsAndLeavesNoSet() {
        val a = payload(10_000, 9)
        files["/a.bin"] = a
        val catalog = Catalog(sets = listOf(CatalogSet("t", "lite", "T", files = listOf(CatalogFile("a.bin", a.size.toLong(), "0".repeat(64), url = "$base/a.bin")))))
        try {
            installer(catalog).install(catalog.set("t")!!, { false }) { _, _, _ -> }
            fail("ожидалось исключение")
        } catch (e: DownloadIntegrityException) {
            assertTrue(e.message!!.contains("Контрольная сумма"))
        }
        assertEquals(SetState.MISSING, store.state("t"))
        assertFalse(File(store.setDir("t"), "a.bin").exists())
    }

    @Test
    fun cancelKeepsPartialForLaterResume() {
        val a = payload(2_000_000, 11)
        files["/a.bin"] = a
        val catalog = Catalog(sets = listOf(CatalogSet("t", "lite", "T", files = listOf(CatalogFile("a.bin", a.size.toLong(), sha(a), url = "$base/a.bin")))))
        var cancelled = false
        val inst = installer(catalog)
        try {
            inst.install(catalog.set("t")!!, { cancelled }) { done, _, _ -> if (done > 600_000) cancelled = true }
            fail("ожидалась отмена")
        } catch (e: DownloadCancelledException) { /* ожидаемо */ }
        assertTrue(inst.partialBytes("t") in 1 until a.size)
        assertEquals(SetState.MISSING, store.state("t"))
        // продолжение с того же места
        inst.install(catalog.set("t")!!, { false }) { _, _, _ -> }
        assertEquals(SetState.COMPLETE, store.state("t"))
        assertTrue(rangeRequests.get() >= 1)
    }

    @Test
    fun notEnoughSpaceFailsBeforeAnyRequest() {
        val a = payload(10_000, 1)
        files["/a.bin"] = a
        val catalog = Catalog(sets = listOf(CatalogSet("t", "lite", "T", files = listOf(CatalogFile("a.bin", a.size.toLong(), sha(a), url = "$base/a.bin")))))
        try {
            installer(catalog, space = { 1_000 }).install(catalog.set("t")!!, { false }) { _, _, _ -> }
            fail("ожидалась нехватка места")
        } catch (e: InsufficientSpaceException) {
            assertTrue(e.neededBytes > e.availableBytes)
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun wrongSizeFromServerIsIntegrityError() {
        val a = payload(1_000, 1)
        files["/a.bin"] = a
        val catalog = Catalog(sets = listOf(CatalogSet("t", "lite", "T", files = listOf(CatalogFile("a.bin", 2_000, null, url = "$base/a.bin")))))
        try {
            installer(catalog).install(catalog.set("t")!!, { false }) { _, _, _ -> }
            fail("ожидалась ошибка размера")
        } catch (e: DownloadIntegrityException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun missingArchiveMemberIsReported() {
        val archive = tarBz2(mapOf("pkg/enc.onnx" to payload(10, 3)))
        files["/pkg.tar.bz2"] = archive
        val catalog = Catalog(
            archives = listOf(CatalogArchive("pkg", "$base/pkg.tar.bz2", archive.size.toLong(), stripComponents = 1)),
            sets = listOf(CatalogSet("t", "lite", "T", files = listOf(CatalogFile("x.onnx", 10, null, archive = "pkg", member = "nope.onnx")))),
        )
        try {
            installer(catalog).install(catalog.set("t")!!, { false }) { _, _, _ -> }
            fail("ожидалась ошибка")
        } catch (e: DownloadIntegrityException) {
            assertTrue(e.message!!.contains("nope.onnx"))
        }
    }
}
