package ru.neurosteno.app.data

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.neurosteno.app.TestData

class HistoryRepositoryTest {
    private lateinit var root: java.io.File
    private var now = 1_000_000L
    private lateinit var repo: HistoryRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("history").toFile()
        repo = HistoryRepository(root) { now++ }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test
    fun addStoresEntryTranscriptAndAudio() {
        val e = repo.add("встреча.m4a", TestData.dialog(), peakPssMb = 500, modelSetId = "medium", audio = { ByteArrayInputStream(ByteArray(10) { it.toByte() }) })
        assertEquals("встреча", e.title)
        assertEquals(2, e.speakerCount)
        assertEquals(3, e.utteranceCount)
        assertEquals("добрый день", e.preview)
        assertEquals(20_000, e.durationMs)
        assertEquals(0.3, e.rtf, 1e-9)
        val audio = repo.audioFile(e)
        assertNotNull(audio)
        assertEquals("audio.m4a", audio!!.name)
        assertEquals(10, audio.length())
        assertEquals(3, repo.loadTranscript(e.id)!!.utterances.size)
        assertEquals(listOf(e.id), repo.entries.value.map { it.id })
    }

    @Test
    fun failedAudioCopyDoesNotLoseTranscript() {
        val e = repo.add("a.wav", TestData.dialog(), audio = { throw java.io.IOException("нет доступа") })
        assertNull(e.audioFile)
        assertNull(repo.audioFile(e))
        assertNotNull(repo.loadTranscript(e.id))
    }

    @Test
    fun entriesSortedNewestFirstAndSurviveReload() {
        val a = repo.add("a.wav", TestData.dialog())
        val b = repo.add("b.wav", TestData.dialog())
        assertEquals(listOf(b.id, a.id), repo.entries.value.map { it.id })
        val again = HistoryRepository(root)
        assertEquals(listOf(b.id, a.id), again.entries.value.map { it.id })
    }

    @Test
    fun saveTranscriptUpdatesCard() {
        val e = repo.add("a.wav", TestData.dialog())
        val edited = StoredTranscript.of(TestData.dialog().let { it.copy(utterances = it.utterances.take(1)) }, mapOf("speaker_1" to "Мурзик"), edited = true)
        val updated = repo.saveTranscript(e.id, edited)!!
        assertEquals(1, updated.utteranceCount)
        assertTrue(updated.edited)
        assertEquals(e.processingMs, updated.processingMs)
        assertEquals("Мурзик", repo.loadTranscript(e.id)!!.speakerNames["speaker_1"])
    }

    @Test
    fun protocolRoundTripAndDelete() {
        val e = repo.add("a.wav", TestData.dialog())
        assertNull(repo.readProtocol(e.id))
        repo.writeProtocol(e.id, "Протокол")
        assertEquals("Протокол", repo.readProtocol(e.id))
        repo.delete(e.id)
        assertTrue(repo.entries.value.isEmpty())
        assertFalse(repo.dir(e.id).exists())
    }

    @Test
    fun clearRemovesEverything() {
        repo.add("a.wav", TestData.dialog()); repo.add("b.wav", TestData.dialog())
        repo.clear()
        assertTrue(repo.entries.value.isEmpty())
        assertEquals(0, repo.usedBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafeIdIsRejected() {
        repo.dir("../x")
    }
}
