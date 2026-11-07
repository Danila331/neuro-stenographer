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
        lid: LanguageId? = null,
        asrEn: AsrEngine? = null,
    ) = PipelineOrchestrator(
        decoder,
        EngineProviders(
            vad = { vad },
            asr = { lang -> if (lang == "en") checkNotNull(asrEn) else asr },
            diarizer = { diar },
            punctuator = punct?.let { p -> { _: Long -> p } },
            denoiser = denoiser?.let { d -> { d } },
            languageId = lid?.let { l -> { l } },
        ),
        s,
        Dispatchers.IO,
    )

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

    private class FakeLid(val answer: String? = null, val fail: Throwable? = null) : LanguageId {
        var calls = 0
        override fun detect(audio: AudioBuffer, speech: List<TimeSpan>): String {
            calls++
            fail?.let { throw it }
            return answer!!
        }
        override fun close() = Unit
    }

    private val bilingual = settings.copy(languageMode = LanguageConfig.AUTO, supportedLanguages = setOf("ru", "en"))

    @Test
    fun `auto language picks english asr when lid says en`() = runBlocking {
        val en = FakeAsr(listOf(Word("hello", 0, 500), Word("world", 600, 1000)))
        val r = orchestrator(lid = FakeLid("en"), asrEn = en, s = bilingual).run(input)
        assertEquals("hello world", r.transcript.utterances.single().text)
        assertEquals("en", r.transcript.meta.language)
        assertTrue(r.transcript.meta.stageMs.containsKey("lid"))
    }

    @Test
    fun `auto language falls back to ru on lid failure or garbage`() = runBlocking {
        val failed = orchestrator(lid = FakeLid(fail = IllegalStateException("lid")), s = bilingual).run(input)
        assertEquals("ru", failed.transcript.meta.language)
        assertEquals("привет мир", failed.transcript.utterances.single().text)
        assertTrue(failed.warnings.any { it.contains("lid") })
        val garbage = orchestrator(lid = FakeLid("zh"), s = bilingual).run(input)
        assertEquals("ru", garbage.transcript.meta.language)
    }

    @Test
    fun `fixed language modes skip lid and unsupported language degrades to ru`() = runBlocking {
        val lid = FakeLid("en")
        val ru = orchestrator(lid = lid, s = settings.copy(languageMode = "ru")).run(input)
        assertEquals(0, lid.calls)
        assertEquals("ru", ru.transcript.meta.language)
        // en запрошен, но английской ASR нет -> ru + предупреждение
        val unsupported = orchestrator(s = settings.copy(languageMode = "en")).run(input)
        assertEquals("ru", unsupported.transcript.meta.language)
        assertTrue(unsupported.warnings.any { it.contains("не поддерживается") })
        // en запрошен вручную и поддерживается
        val en = orchestrator(asrEn = FakeAsr(listOf(Word("ok", 0, 300))), s = settings.copy(languageMode = "en", supportedLanguages = setOf("ru", "en"))).run(input)
        assertEquals("en", en.transcript.meta.language)
    }

    @Test
    fun `engines are closed one by one - asr and diarizer closed before punctuator is created`() = runBlocking {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        fun <T : AutoCloseable> T.logged(name: String): T = this.also { events += "create:$name" }
        val asr = object : AsrEngine {
            override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener) = listOf(Word("привет", 0, 500))
            override fun close() { events += "close:asr" }
        }
        val diar = object : Diarizer {
            override fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener) = emptyList<SpeakerTurn>()
            override fun close() { events += "close:diar" }
        }
        val punct = object : Punctuator {
            override fun punctuate(utterances: List<Utterance>, progress: ProgressListener) = utterances
            override fun close() { events += "close:punct" }
        }
        val vad = object : Vad {
            override fun detect(audio: AudioBuffer) = listOf(TimeSpan(0, audio.durationMs))
            override fun close() { events += "close:vad" }
        }
        PipelineOrchestrator(
            FakeDecoder(),
            EngineProviders(
                vad = { vad.logged("vad") },
                asr = { asr.logged("asr") },
                diarizer = { diar.logged("diar") },
                punctuator = { _ -> punct.logged("punct") },
            ),
            settings,
            Dispatchers.IO,
        ).run(input)
        assertTrue(events.indexOf("close:vad") < events.indexOf("create:asr"), "$events")
        assertTrue(events.indexOf("close:asr") < events.indexOf("create:punct"), "$events")
        assertTrue(events.indexOf("close:diar") < events.indexOf("create:punct"), "$events")
        assertEquals("close:punct", events.last())
    }

    @Test
    fun `engine is closed even when it fails`() {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val asr = object : AsrEngine {
            override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word> = error("x")
            override fun close() { closed.set(true) }
        }
        assertThrows<PipelineException> { runBlocking { orchestrator(asr = asr).run(input) } }
        assertTrue(closed.get())
    }

    @Test
    fun `resource plan can switch llm punctuation off without failing`() = runBlocking {
        val punct = FakePunctuator { it.copy(text = "Привет, мир!") }
        val plan = ResourcePlan(numThreads = 1, llmPunctuation = false, secondPass = false, denoise = false, reasons = listOf("мало памяти"))
        val r = orchestrator(punct = punct, s = settings.copy(plan = plan)).run(input)
        assertEquals("Привет мир.", r.transcript.utterances.single().text) // LLM не вызывался, fallback
        assertTrue(r.warnings.any { it.contains("мало памяти") })
    }

    @Test
    fun `punctuator factory failure is not fatal`() = runBlocking {
        val r = PipelineOrchestrator(
            FakeDecoder(),
            EngineProviders(vad = { FakeVad() }, asr = { FakeAsr() }, diarizer = { FakeDiarizer() }, punctuator = { _ -> error("gguf missing") }),
            settings,
            Dispatchers.IO,
        ).run(input)
        assertEquals("Привет мир.", r.transcript.utterances.single().text)
        assertTrue(r.warnings.any { it.contains("punctuation") })
    }

    @Test
    fun `normalize gain is applied before vad and engines see the amplified audio`() = runBlocking {
        val quiet = AudioBuffer(FloatArray(16_000 * 3) { if (it % 2 == 0) 0.01f else -0.01f })
        val vad = FakeVad()
        orchestrator(decoder = FakeDecoder(quiet), vad = vad, s = settings.copy(normalizeGain = NormalizeGainConfig(enabled = true, mode = "peak"))).run(input)
        assertEquals(0.891f, vad.seen!!.samples.maxOf { kotlin.math.abs(it) }, 0.002f) // -1 dBFS
    }

    @Test
    fun `asr_native words keep punctuation and case all the way to json`() = runBlocking {
        val words = listOf(Word("Добрый", 0, 500), Word("день,", 550, 900), Word("коллеги!", 950, 1500))
        // пунктуатора нет (asr_native) -> текст = слова как есть, без guard
        val r = orchestrator(asr = FakeAsr(words)).run(input)
        assertEquals("Добрый день, коллеги!", r.transcript.utterances.single().text)
        assertTrue(r.json.contains("Добрый день, коллеги!"))
    }

    @Test
    fun `per chunk language runs ru asr then en asr sequentially on their own chunks and merges words`() = runBlocking {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val seen = java.util.Collections.synchronizedMap(HashMap<String, List<TimeSpan>>())
        fun asrFor(lang: String, text: String) = object : AsrEngine {
            override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word> {
                events += "run:$lang"; seen[lang] = speech
                progress.onProgress(Stage.ASR, 1f)
                return speech.map { Word("$text${it.startMs}", it.startMs, it.startMs + 100) }
            }
            override fun close() { events += "close:$lang" }
        }
        val lid = object : LanguageId {
            override fun detect(audio: AudioBuffer, speech: List<TimeSpan>) = if (speech.single().startMs >= 30_000) "en" else "ru"
            override fun close() { events += "close:lid" }
        }
        // четыре интервала по 9 с с большими паузами: 0 и 20 с -- ru, 40 и 60 с -- en
        val vad = FakeVad(spans = listOf(TimeSpan(0, 9_000), TimeSpan(20_000, 29_000), TimeSpan(40_000, 49_000), TimeSpan(60_000, 69_000)))
        val r = PipelineOrchestrator(
            FakeDecoder(silence(70_000)),
            EngineProviders(
                vad = { vad },
                asr = { lang -> events += "create:$lang"; asrFor(lang, lang) },
                diarizer = { FakeDiarizer() },
                languageId = { lid },
            ),
            settings.copy(languageMode = "auto", supportedLanguages = setOf("ru", "en"), perChunkLanguage = true, chunkMaxMs = 25_000),
            Dispatchers.IO,
        ).run(input)
        // при max 25 с соседние интервалы не склеиваются (расстояние > 25 с): четыре чанка; LID: ru, ru, en, en
        assertEquals(listOf(TimeSpan(0, 9_000), TimeSpan(20_000, 29_000)), seen["ru"])
        assertEquals(listOf(TimeSpan(40_000, 49_000), TimeSpan(60_000, 69_000)), seen["en"])
        assertTrue(events.indexOf("close:ru") < events.indexOf("create:en"), "$events")
        assertTrue(events.indexOf("close:lid") < events.indexOf("create:ru"), "$events")
        assertEquals("ru", r.transcript.meta.language) // поровну 18 с / 18 с -- побеждает ru
        assertEquals(listOf("ru0", "ru20000", "en40000", "en60000").size, r.transcript.utterances.sumOf { it.words.size })
    }

    @Test
    fun `numbers are spelled when postprocess numbers is words`() = runBlocking {
        val r = orchestrator(asr = FakeAsr(listOf(Word("в", 0, 100), Word("25", 100, 600), Word("мышей", 600, 900))), s = settings.copy(spellNumbers = true)).run(input)
        assertEquals("в двадцать пять мышей", r.transcript.utterances.single().text)
    }
}
