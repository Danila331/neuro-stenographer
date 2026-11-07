package ru.neurosteno.app.util

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.neurosteno.app.data.HistoryEntry

class OwnRecordingsTest {
    private lateinit var root: File
    private lateinit var recordings: File
    private lateinit var rec: File

    @Before fun setUp() {
        root = Files.createTempDirectory("rec").toFile()
        recordings = File(root, "recordings").apply { mkdirs() }
        rec = File(recordings, "rec-1.wav").apply { writeBytes(ByteArray(10)) }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun entry(audio: String?) = HistoryEntry(id = "1", sourceName = "rec-1.wav", createdAt = 0, durationMs = 1, speakerCount = 1, utteranceCount = 1, preview = "", audioFile = audio)

    @Test
    fun originalIsKeptWhenHistoryEntryWasNotSaved() {
        assertFalse(OwnRecordings.deleteIfSaved(rec, recordings, null))
        assertTrue(rec.exists())
    }

    @Test
    fun originalIsKeptWhenEntryExistsButAudioCopyFailed() {
        assertFalse(OwnRecordings.deleteIfSaved(rec, recordings, entry(null)))
        assertTrue(rec.exists())
    }

    @Test
    fun originalIsDeletedOnlyAfterConfirmedCopy() {
        assertTrue(OwnRecordings.deleteIfSaved(rec, recordings, entry("audio.wav")))
        assertFalse(rec.exists())
    }

    @Test
    fun foreignFilesAreNeverDeleted() {
        val other = File(root, "other.wav").apply { writeBytes(ByteArray(3)) }
        assertFalse(OwnRecordings.deleteIfSaved(other, recordings, entry("audio.wav")))
        assertTrue(other.exists())
    }
}
