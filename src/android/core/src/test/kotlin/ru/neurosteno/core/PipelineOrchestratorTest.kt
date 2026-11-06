package ru.neurosteno.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PipelineOrchestratorTest {
    private val input = FileAudioInput("/tmp/test.wav")
    private val settings = PipelineSettings(tier = "lite", appVersion = "t")

    private fun orchestrator(
        decoder: AudioDecoder = FakeDecoder(),
        vad: Vad = FakeVad(),
        asr: AsrEngine = FakeAsr(),
        diar: Diarizer = FakeDiarizer(),
        punct: Punctuator? = null,
        denoiser: Denoiser? = null,
        s: PipelineSettings = settings,
    ) = PipelineOrchestrator(decoder, vad, asr, diar, s, punct, denoiser, Dispatchers.IO)

    @Test
    fun `happy path produces spec json with meta and stage times`() = runBlocking {
        val r = orchestrator(punct = FakePunctuator { it.copy(text = "Привет, мир!") }).run(input)
        assertEquals("""{"transcript":[{"speaker_id":"speaker_1","start_ms":100,"end_ms":1200,"text":"Привет, мир!"}]}""", r.json)
        assertEquals(r.json, TranscriptJson.toJson(r.transcript))
        assertEquals(10_000L, r.transcript.meta.audioDurationMs)
        assertEquals("lite", r.transcript.meta.tier)
        val st = r.transcript.meta.stageMs
        for (k in listOf("decode", "vad", "asr", "diarization", "align", "punctuation", "export", "total")) {
            assertTrue(st.containsKey(k), "stageMs has $k: $st")
        }
        assertFalse(st.containsKey("denoise"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `asr and diarization really run in parallel`() = runBlocking {
        val latch = CountDownLatch(2) // каждый ждёт, пока стартует второй; при последовательном запуске -- таймаут
        val r = orchestrator(asr = FakeAsr(rendezvous = latch), diar = FakeDiarizer(rendezvous = latch)).run(input)
        assertEquals(1, r.transcript.utterances.size)
    }

    @Test
    fun `diarization failure degrades to single speaker with warning`() = runBlocking {
        val r = orchestrator(diar = FakeDiarizer(fail = IllegalStateException("onnx boom"))).run(input)
        assertEquals(1, r.transcript.utterances.size)
        assertEquals(listOf("speaker_1"), r.transcript.utterances.map { it.speakerId })
        assertTrue(r.warnings.single().contains("diarization"))
    }

    @Test
    fun `asr failure is fatal with stage`() {
        val e = assertThrows<PipelineException> {
            runBlocking { orchestrator(asr = FakeAsr(fail = OutOfMemoryError("oom"))).run(input) }
        }
        assertEquals(Stage.ASR, e.stage)
    }

    @Test
    fun `decode failure is fatal with stage`() {
        val e = assertThrows<PipelineException> {
            runBlocking { orchestrator(decoder = FakeDecoder(fail = java.io.IOException("bad file"))).run(input) }
        }
        assertEquals(Stage.DECODE, e.stage)
        assertTrue(e.message!!.contains("bad file"))
    }

    @Test
    fun `vad failure falls back to whole file and vad silence gives empty transcript`() = runBlocking {
        val fb = orchestrator(vad = FakeVad(fail = RuntimeException("vad"))).run(input)
        assertEquals(1, fb.transcript.utterances.size)
        assertEquals(1, fb.warnings.size)

        val none = orchestrator(vad = FakeVad(spans = emptyList())).run(input)
        assertEquals("""{"transcript":[]}""", none.json)
        assertTrue(none.transcript.utterances.isEmpty())
    }

    @Test
    fun `empty audio gives empty transcript without calling engines`() = runBlocking {
        val asr = FakeAsr(fail = IllegalStateException("must not be called"))
        val r = orchestrator(decoder = FakeDecoder(AudioBuffer(FloatArray(0))), asr = asr).run(input)
        assertEquals("""{"transcript":[]}""", r.json)
    }

    @Test
    fun `punctuator failure or word change falls back to guarded text`() = runBlocking {
        val boom = orchestrator(punct = object : Punctuator {
            override fun punctuate(utterances: List<Utterance>, progress: ProgressListener): List<Utterance> = error("llm")
            override fun close() = Unit
        }).run(input)
        assertEquals("Привет мир.", boom.transcript.utterances.single().text)

        val liar = orchestrator(punct = FakePunctuator { it.copy(text = "Здравствуй, мир!") }).run(input)
        assertEquals("Привет мир.", liar.transcript.utterances.single().text)
    }

    @Test
    fun `two speakers are aligned and numbered by appearance`() = runBlocking {
        val words = listOf(Word("добрый", 0, 500), Word("день", 550, 900), Word("здравствуйте", 2000, 2800))
        val turns = listOf(SpeakerTurn(3, 0, 1000), SpeakerTurn(1, 1900, 3000))
        val r = orchestrator(asr = FakeAsr(words), diar = FakeDiarizer(turns)).run(input)
        assertEquals(listOf("speaker_1", "speaker_2"), r.transcript.utterances.map { it.speakerId })
        assertEquals(listOf("добрый день", "здравствуйте"), r.transcript.utterances.map { it.text })
    }

    @Test
    fun `denoise runs only when enabled and snr below threshold`() = runBlocking {
        val noisy = AudioBuffer(FloatArray(16_000 * 6) { if (it % 2 == 0) 0.3f else -0.3f }) // постоянный «шум», SNR ~ 0
        val dn = FakeDenoiser()
        val vad = FakeVad()
        orchestrator(decoder = FakeDecoder(noisy), denoiser = dn, vad = vad, s = settings.copy(denoiseEnabled = true)).run(input)
        assertTrue(dn.called.get())
        assertEquals(0.123f, vad.seen!!.samples[0]) // дальше идёт очищенное аудио

        val dn2 = FakeDenoiser()
        orchestrator(decoder = FakeDecoder(noisy), denoiser = dn2, s = settings.copy(denoiseEnabled = false)).run(input)
        assertFalse(dn2.called.get())
    }

    @Test
    fun `num speakers from settings is passed to diarizer`() = runBlocking {
        val d = FakeDiarizer()
        orchestrator(diar = d, s = settings.copy(numSpeakers = 2)).run(input)
        assertEquals(2, d.requestedSpeakers)
    }

    @Test
    fun `progress is monotonic ends at one and covers all stages`() = runBlocking {
        val seen = java.util.Collections.synchronizedList(ArrayList<Float>())
        val stages = java.util.Collections.synchronizedSet(HashSet<Stage>())
        orchestrator().run(input) { stage, _, overall -> seen += overall; stages += stage }
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "monotonic: $seen")
        assertEquals(1f, seen.last(), 1e-4f)
        assertEquals(Stage.entries.toSet(), stages)
    }

    @Test
    fun `cancellation stops a long running asr quickly`() = runBlocking {
        val entered = CountDownLatch(1)
        val iterations = AtomicInteger()
        val slowAsr = object : AsrEngine {
            override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word> {
                entered.countDown()
                while (true) { // «движок», который репортит прогресс раз в ~10 мс
                    iterations.incrementAndGet()
                    progress.onProgress(Stage.ASR, 0.1f)
                    Thread.sleep(10)
                }
            }
            override fun close() = Unit
        }
        val job = async(Dispatchers.Default) { orchestrator(asr = slowAsr).run(input) }
        entered.await()
        job.cancel()
        withTimeout(3_000) {
            assertThrows<CancellationException> { job.await() }
        }
        val after = iterations.get()
        delay(100)
        assertTrue(iterations.get() - after <= 2, "engine kept running after cancel")
    }

    @Test
    fun `cancellation during decode is not wrapped into pipeline exception`() = runBlocking {
        val started = CountDownLatch(1)
        val blockingDecoder = object : AudioDecoder {
            override fun decode(input: AudioInput, progress: ProgressListener): AudioBuffer {
                started.countDown()
                while (true) { progress.onProgress(Stage.DECODE, 0f); Thread.sleep(5) }
            }
        }
        val job = launch(Dispatchers.Default) { orchestrator(decoder = blockingDecoder).run(input) }
        started.await()
        job.cancel()
        withTimeout(3_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `yo normalization from settings is applied`() = runBlocking {
        val r = orchestrator(
            asr = FakeAsr(listOf(Word("всё", 0, 500))),
            s = settings.copy(normalization = NormalizationConfig(yoToE = true)),
        ).run(input)
        assertEquals("все", r.transcript.utterances.single().text)
    }
}
