package ru.neurosteno.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.neurosteno.app.TestData

class StoredTranscriptTest {
    @Test
    fun roundTripKeepsEverything() {
        val src = TestData.dialog()
        val stored = StoredTranscript.of(src, mapOf("speaker_1" to "Мурзик"), edited = true)
        val back = StoredTranscript.decode(stored.encode())
        assertEquals(stored, back)
        val tr = back.toTranscript()
        assertEquals(src.utterances, tr.utterances)
        assertEquals(src.meta, tr.meta)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = StoredTranscript.of(TestData.dialog()).encode().replaceFirst("{", "{\"future\":1,")
        assertEquals(3, StoredTranscript.decode(text).utterances.size)
    }
}
