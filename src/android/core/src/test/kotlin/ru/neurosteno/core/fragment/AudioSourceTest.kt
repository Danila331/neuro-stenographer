package ru.neurosteno.core.fragment

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.neurosteno.core.Aligner
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.AudioDecoder
import ru.neurosteno.core.AudioInput
import ru.neurosteno.core.FileAudioInput
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.Word
import ru.neurosteno.core.audio.Pcm
import ru.neurosteno.core.audio.WavWriter

class AudioSourceTest {
    private fun ramp(ms: Int, rate: Int = 16_000) = FloatArray(ms * rate / 1000) { ((it % 20_000) - 10_000) / 10_001f }

    private fun tmp(): File = Files.createTempFile("src", ".wav").toFile().also { it.deleteOnExit() }

    @Test
    fun `buffer source returns a clamped copy`() {
        val a = AudioBuffer(ramp(1000))
        val s = AudioSource.of(a)
        assertEquals(1000L, s.durationMs)
        val r = s.read(100, 300)
        assertEquals(200L, r.durationMs)
        assertArrayEquals(a.samples.copyOfRange(1600, 4800), r.samples)
        r.samples[0] = 5f
        assertTrue(a.samples[1600] != 5f, "copy")
        assertEquals(0, s.read(5_000, 6_000).samples.size)
        assertEquals(1000L, s.read(-5, 99_999).durationMs)
    }

    @Test
    fun `wav file source reads ranges directly and matches the full decoder`() {
        val f = tmp()
        val a = AudioBuffer(ramp(3000))
        WavWriter.write(f, a)
        val s = AudioSource.wav(f)
        assertEquals(3000L, s.durationMs)
        val r = s.read(1000, 1500)
        assertEquals(500L, r.durationMs)
        val expected = Pcm.floatsToShorts(a.samples).copyOfRange(16_000, 24_000).map { it / 32768f }.toFloatArray()
        assertArrayEquals(expected, r.samples, 1e-6f)
        assertEquals(0, s.read(4_000, 5_000).samples.size)
    }

    @Test
    fun `wav file with another sample rate falls back to full decoding`() {
        val f = tmp()
        WavWriter(f, 8_000).use { it.write(Pcm.floatsToShorts(ramp(2000, 8_000))) }
        val s = AudioSource.wav(f)
        assertEquals(2000L, s.durationMs)
        assertEquals(500L, s.read(0, 500).durationMs)
    }

    @Test
    fun `decoded source decodes lazily once`() {
        var calls = 0
        val dec = object : AudioDecoder {
            override fun decode(input: AudioInput, progress: ru.neurosteno.core.ProgressListener): AudioBuffer {
                calls++
                return AudioBuffer(ramp(1000))
            }
        }
        val s = AudioSource.decoded(FileAudioInput("/x.m4a"), dec)
        assertEquals(0, calls)
        s.read(0, 100)
        s.read(100, 200)
        assertEquals(1000L, s.durationMs)
        assertEquals(1, calls)
    }

    @Test
    fun `aligner without renumbering keeps the speaker labels`() {
        val words = listOf(Word("а", 0, 100), Word("б", 200, 300), Word("в", 5000, 5100))
        val turns = listOf(SpeakerTurn(7, 0, 1000), SpeakerTurn(3, 4000, 6000))
        val kept = Aligner().align(words, turns, renumber = false)
        assertEquals(listOf("speaker_7", "speaker_3"), kept.map { it.speakerId })
        val renum = Aligner().align(words, turns)
        assertEquals(listOf("speaker_1", "speaker_2"), renum.map { it.speakerId })
        assertEquals(renum.map { it.copy(speakerId = "") }, kept.map { it.copy(speakerId = "") })
    }
}
