package ru.neurosteno.core.fragment

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.neurosteno.core.AsrEngine
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.Diarizer
import ru.neurosteno.core.EngineProviders
import ru.neurosteno.core.LanguageConfig
import ru.neurosteno.core.PipelineException
import ru.neurosteno.core.PipelineSettings
import ru.neurosteno.core.Stage
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptJson
import ru.neurosteno.core.TranscriptMeta
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word

class FragmentReprocessorTest {
    private val w = World()
    private val vad = WorldVad(w)
    private val asrLog: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var diarizer: WorldDiarizer? = null
    private val langsRequested = Collections.synchronizedList(ArrayList<String>())

    private fun utt(id: String, s: Long, e: Long, vararg texts: String): Utterance {
        val step = (e - s) / texts.size
        val words = texts.mapIndexed { i, t -> Word(t, s + i * step, s + (i + 1) * step - 50) }
        return Utterance(id, s, e, texts.joinToString(" "), words)
    }

    /** Старая расшифровка: A (speaker_1), B (speaker_2), A, C (speaker_3). */
    private val u1 = utt("speaker_1", 1_000, 4_000, "привет", "всем")
    private val u2 = utt("speaker_2", 5_000, 9_000, "втроая", "реплика", "плохо")
    private val u3 = utt("speaker_1", 10_000, 14_000, "третья", "реплика")
    private val u4 = utt("speaker_3", 15_000, 19_000, "четвёртая", "реплика")
    private val old = listOf(u1, u2, u3, u4)

    private fun transcript(utts: List<Utterance> = old) = Transcript(
        utts, TranscriptMeta("t", w.durationMs, 5_000, mapOf("total" to 5_000L), emptyMap(), "test"),
    )

    init {
        w.speakers = listOf(Triple(1_000L, 4_000L, 0), Triple(5_000L, 9_000L, 1), Triple(10_000L, 14_000L, 0), Triple(15_000L, 19_000L, 2))
        w.words = mapOf(
            "ru" to listOf(
                Word("привет", 1_000, 1_900), Word("всем", 2_500, 3_900),
                Word("вторая", 5_100, 5_900), Word("реплика", 6_000, 7_000), Word("исправлена", 7_100, 8_900),
                Word("третья", 10_000, 11_000), Word("реплика", 12_000, 13_900),
                Word("четвёртая", 15_100, 16_900), Word("реплика", 17_000, 18_900),
            ),
            "en" to listOf(Word("second", 5_100, 5_900), Word("line", 6_000, 7_000), Word("fixed", 7_100, 8_900)),
        )
    }

    private fun reprocessor(
        s: PipelineSettings = PipelineSettings(tier = "t", supportedLanguages = setOf("ru", "en")),
        diar: WorldDiarizer? = WorldDiarizer(w, mapOf(0 to 7, 1 to 3, 2 to 5, 3 to 9)),
        lid: String? = null,
        tuning: FragmentTuning = FragmentTuning(),
    ): FragmentReprocessor {
        diarizer = diar
        return FragmentReprocessor(
            EngineProviders(
                vad = { vad },
                asr = { lang -> langsRequested += lang; WorldAsr(w, lang, asrLog) as AsrEngine },
                diarizer = { checkNotNull(diar) { "diarizer must not be created" } as Diarizer },
                languageId = lid?.let { a -> { FixedLid(a) } },
            ),
            s, tuning, Dispatchers.IO,
        )
    }

    private fun run(
        r: FragmentReprocessor,
        req: FragmentRequest,
        t: Transcript = transcript(),
        progress: ((Stage, Float, Float) -> Unit)? = null,
    ) = runBlocking { r.reprocess(w.audio, t, req) { st, f, o -> progress?.invoke(st, f, o) } }

    private fun assertContract(r: FragmentResult) {
        val items = TranscriptJson.parse(r.json)
        assertEquals(r.transcript.utterances.size, items.size)
        assertEquals(items.sortedWith(compareBy({ it.startMs }, { it.endMs })), items)
        var next = 1
        val seen = LinkedHashMap<String, Int>()
        for (i in items) {
            val n = seen.getOrPut(i.speakerId) { next++ }
            assertEquals("speaker_$n", i.speakerId, "speakers numbered by first appearance")
            assertTrue(i.startMs in 0 until i.endMs && i.endMs <= w.durationMs)
            assertTrue(i.text.isNotEmpty() && i.text == i.text.trim())
        }
        assertEquals(r.json, TranscriptJson.toJson(r.transcript))
    }

    // ------------------------------------------------------------------ замена

    @Test
    fun `replaces exactly the intersecting utterance and keeps the rest untouched`() {
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000))
        assertEquals(FragmentOutcome.REPLACED, r.outcome)
        val us = r.transcript.utterances
        assertEquals(4, us.size)
        assertEquals(u1, us[0])
        assertEquals(u3, us[2])
        assertEquals(u4, us[3])
        assertEquals("вторая реплика исправлена", us[1].text)
        assertEquals("speaker_2", us[1].speakerId, "говорящий взят у старой реплики")
        assertEquals(5_100L, us[1].startMs)
        assertEquals(8_900L, us[1].endMs)
        assertEquals(listOf(u2), r.removed)
        assertEquals(listOf(us[1]), r.added)
        assertEquals(TimeSpan(5_000, 9_000), r.range)
        assertContract(r)
        // ASR получил отрезок с паддингом 300 мс: [4700, 9300]
        assertEquals(listOf("ru@4700+4600"), asrLog)
        assertEquals(0, diarizer!!.diarizeCalls, "без recomputeSpeakers диаризация не запускается")
    }

    @Test
    fun `request inside an utterance replaces the whole utterance and range expands to it`() {
        val r = run(reprocessor(), FragmentRequest(6_000, 7_000))
        assertEquals(TimeSpan(5_000, 9_000), r.range)
        assertEquals(listOf(u2), r.removed)
        assertEquals("вторая реплика исправлена", r.transcript.utterances[1].text)
    }

    @Test
    fun `several utterances in one request`() {
        val r = run(reprocessor(), FragmentRequest(8_000, 15_500))
        assertEquals(listOf(u2, u3, u4), r.removed)
        assertEquals(TimeSpan(5_000, 19_000), r.range)
        assertEquals(listOf("привет всем", "вторая реплика исправлена", "третья реплика", "четвёртая реплика"), r.transcript.utterances.map { it.text })
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_3"), r.transcript.utterances.map { it.speakerId })
        assertContract(r)
    }

    @Test
    fun `touching a boundary does not replace the neighbour`() {
        // u2.end == 9000, u3.start == 10000: отрезок [9000, 10000] никого не пересекает, речи там нет
        val r = run(reprocessor(), FragmentRequest(9_000, 10_000))
        assertEquals(FragmentOutcome.NO_SPEECH_KEPT, r.outcome)
        assertEquals(old, r.transcript.utterances)
        assertTrue(r.removed.isEmpty())
    }

    @Test
    fun `speech found where there were no utterances is inserted`() {
        w.words = w.words + ("ru" to w.words.getValue("ru") + Word("пропущено", 21_000, 22_000))
        w.speakers = w.speakers + Triple(20_500L, 22_500L, 2)
        val r = run(reprocessor(), FragmentRequest(20_000, 23_000))
        assertEquals(FragmentOutcome.INSERTED, r.outcome)
        assertEquals(5, r.transcript.utterances.size)
        val added = r.transcript.utterances.last()
        assertEquals("пропущено", added.text)
        assertEquals("speaker_3", added.speakerId, "говорящий ближайшей реплики")
        assertEquals(listOf(added), r.added)
        assertTrue(r.removed.isEmpty())
        assertContract(r)
    }

    @Test
    fun `words from padding are dropped`() {
        // слово 4_800..4_950 лежит в паддинге [4_700, 5_000) отрезка [5000, 9000] -- в результат не попадает
        w.words = mapOf("ru" to w.words.getValue("ru") + Word("лишнее", 4_700, 4_900), "en" to emptyList())
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000))
        assertEquals("вторая реплика исправлена", r.transcript.utterances[1].text)
    }

    @Test
    fun `neighbour speech in the overlap zone is not duplicated`() {
        // u_a [20000, 24000] заменяем; сосед u_b [23000, 27000] остаётся; ASR отрезка слышит и слова соседа внутри [20000, 24000]
        val a = utt("speaker_1", 20_000, 24_000, "ыыы", "мусор")
        val b = Utterance("speaker_2", 23_000, 27_000, "сосед говорит", listOf(Word("сосед", 23_100, 23_800), Word("говорит", 25_000, 26_000)))
        w.words = mapOf("ru" to w.words.getValue("ru") + listOf(Word("ага", 20_100, 21_000), Word("угу", 21_500, 22_500), Word("сосед", 23_100, 23_800)), "en" to emptyList())
        val r = run(reprocessor(), FragmentRequest(20_000, 22_000), transcript(old + listOf(a, b)))
        val texts = r.transcript.utterances.map { it.text }
        assertEquals(listOf("привет всем", "втроая реплика плохо", "третья реплика", "четвёртая реплика", "ага угу", "сосед говорит"), texts)
        assertTrue(r.warnings.any { it.contains("дублирующих") }, r.warnings.toString())
    }

    // ------------------------------------------------------------------ нет речи

    @Test
    fun `vad finds no speech - utterances are not silently deleted`() {
        vad.noSpeech = true
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000))
        assertEquals(FragmentOutcome.NO_SPEECH_KEPT, r.outcome)
        assertFalse(r.changed)
        assertEquals(old, r.transcript.utterances)
        assertTrue(r.message.contains("не найдена"))
        assertTrue(asrLog.isEmpty(), "ASR не запускается без речи")
    }

    @Test
    fun `asr returns nothing - kept by default, removed on explicit request`() {
        w.words = mapOf("ru" to emptyList(), "en" to emptyList())
        val kept = run(reprocessor(), FragmentRequest(5_000, 9_000))
        assertEquals(FragmentOutcome.NO_SPEECH_KEPT, kept.outcome)
        assertEquals(old, kept.transcript.utterances)

        val removed = run(reprocessor(), FragmentRequest(5_000, 9_000, removeIfNoSpeech = true))
        assertEquals(FragmentOutcome.NO_SPEECH_REMOVED, removed.outcome)
        assertTrue(removed.changed)
        assertEquals(listOf(u2), removed.removed)
        assertEquals(listOf("привет всем", "третья реплика", "четвёртая реплика"), removed.transcript.utterances.map { it.text })
        // speaker_2 исчез -> speaker_3 стал speaker_2
        assertEquals(mapOf("speaker_1" to "speaker_1", "speaker_3" to "speaker_2"), removed.speakerRemap)
        assertContract(removed)
    }

    @Test
    fun `invalid requests return a clear result and leave the transcript unchanged`() {
        for (req in listOf(FragmentRequest(9_000, 5_000), FragmentRequest(70_000, 80_000), FragmentRequest(5_000, 9_000, recomputeSpeakers = true, numSpeakers = 0))) {
            val r = run(reprocessor(), req)
            assertEquals(FragmentOutcome.INVALID_REQUEST, r.outcome, req.toString())
            assertEquals(old, r.transcript.utterances)
        }
        assertTrue(asrLog.isEmpty())
    }

    // ------------------------------------------------------------------ язык

    @Test
    fun `explicit language en selects the english asr`() {
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000, language = "en"))
        assertEquals(listOf("en"), langsRequested)
        assertEquals("en", r.language)
        assertEquals("second line fixed", r.transcript.utterances[1].text)
        assertEquals(listOf("en@4700+4600"), asrLog)
    }

    @Test
    fun `auto language uses lid when available`() {
        val r = run(reprocessor(s = PipelineSettings(tier = "t", languageMode = LanguageConfig.AUTO, supportedLanguages = setOf("ru", "en")), lid = "en"), FragmentRequest(5_000, 9_000))
        assertEquals("en", r.language)
        assertEquals("second line fixed", r.transcript.utterances[1].text)
    }

    @Test
    fun `short fragment does not trust lid and inherits the transcript language`() {
        val short = utt("speaker_1", 30_000, 31_000, "кор")
        w.words = mapOf("ru" to w.words.getValue("ru") + Word("коротко", 30_100, 30_900), "en" to emptyList())
        val r = run(reprocessor(s = PipelineSettings(tier = "t", languageMode = LanguageConfig.AUTO, supportedLanguages = setOf("ru", "en")), lid = "en"),
            FragmentRequest(30_000, 31_000), transcript(old + short))
        assertEquals("ru", r.language)
        assertEquals("коротко", r.transcript.utterances.last().text)
    }

    @Test
    fun `auto language without lid inherits the transcript language`() {
        val t = transcript().let { it.copy(meta = it.meta.copy(language = "en")) }
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000), t)
        assertEquals("en", r.language)
    }

    @Test
    fun `language unsupported by the tier falls back to ru with a warning`() {
        val r = run(reprocessor(s = PipelineSettings(tier = "t")), FragmentRequest(5_000, 9_000, language = "en"))
        assertEquals("ru", r.language)
        assertTrue(r.warnings.any { it.contains("не поддерживается") })
        assertEquals("вторая реплика исправлена", r.transcript.utterances[1].text)
    }

    // ------------------------------------------------------------------ говорящие

    @Test
    fun `recompute speakers matches by embeddings, not by old labels`() {
        // старая метка неверна: реплику [5000, 9000] помечена speaker_1, но голос -- B (speaker_2)
        val wrong = old.map { if (it === u2) it.copy(speakerId = "speaker_1") else it }
        val d = WorldDiarizer(w, mapOf(0 to 7, 1 to 3, 2 to 5, 3 to 9)) // локальные номера не совпадают с глобальными
        val r = run(reprocessor(diar = d), FragmentRequest(5_000, 9_000, recomputeSpeakers = true), transcript(wrong))
        // B нигде вне отрезка не говорит -> нет эталона -> эмбеддингом сопоставить не с кем.
        // Старая метка speaker_1 не считается: у speaker_1 есть эмбеддинг и он не совпал -> новый говорящий
        val assigned = r.speakerAssignments.single()
        assertEquals(3, assigned.localSpeaker)
        assertEquals(SpeakerMatcher.Method.NEW, assigned.method)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_3"), r.transcript.utterances.map { it.speakerId })
        assertEquals(1, d.diarizeCalls)
        assertContract(r)
    }

    @Test
    fun `recompute fixes a wrong speaker label via embedding of a speaker present elsewhere`() {
        // реплика [10000, 14000] (голос A) ошибочно помечена speaker_3; A есть вне отрезка (speaker_1)
        val wrong = old.map { if (it === u3) it.copy(speakerId = "speaker_3") else it }
        val r = run(reprocessor(), FragmentRequest(10_000, 14_000, recomputeSpeakers = true), transcript(wrong))
        val a = r.speakerAssignments.single()
        assertEquals("speaker_1", a.speakerId)
        assertEquals(SpeakerMatcher.Method.EMBEDDING, a.method)
        assertTrue(a.score!! > 0.9)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_3"), r.transcript.utterances.map { it.speakerId })
    }

    @Test
    fun `two speakers in the fragment are matched one-to-one`() {
        val r = run(reprocessor(), FragmentRequest(1_000, 19_000, recomputeSpeakers = true, numSpeakers = 3))
        assertEquals(3, r.speakerAssignments.size)
        assertTrue(r.speakerAssignments.all { it.method == SpeakerMatcher.Method.TIME_OVERLAP }, r.speakerAssignments.toString())
        // весь файл заменён, эталонов вне отрезка нет -> по времени со старыми репликами
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_3"), r.transcript.utterances.map { it.speakerId })
        assertEquals(3, diarizer!!.requested)
    }

    @Test
    fun `new voice becomes a new speaker and numbering follows first appearance`() {
        // истина: в [5000, 9000] говорит C (голос speaker_3), B (speaker_2) нигде больше нет -> speaker_2 исчезает
        w.speakers = listOf(Triple(1_000L, 4_000L, 0), Triple(5_000L, 9_000L, 2), Triple(10_000L, 14_000L, 0), Triple(15_000L, 19_000L, 2))
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000, recomputeSpeakers = true))
        val a = r.speakerAssignments.single()
        assertEquals("speaker_3", a.speakerId)
        assertEquals(SpeakerMatcher.Method.EMBEDDING, a.method)
        // speaker_2 пропал, speaker_3 -> speaker_2 (первое появление по времени)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_2"), r.transcript.utterances.map { it.speakerId })
        assertEquals(mapOf("speaker_1" to "speaker_1", "speaker_3" to "speaker_2"), r.speakerRemap)
        assertContract(r)
    }

    @Test
    fun `completely new speaker gets the next free id and is renumbered`() {
        // D (ось 3) нигде не встречается; заменяем реплику speaker_1 [1000, 4000] -> новый говорящий появляется ПЕРВЫМ
        w.speakers = listOf(Triple(1_000L, 4_000L, 3), Triple(5_000L, 9_000L, 1), Triple(10_000L, 14_000L, 0), Triple(15_000L, 19_000L, 2))
        val r = run(reprocessor(), FragmentRequest(1_000, 4_000, recomputeSpeakers = true))
        val a = r.speakerAssignments.single()
        assertEquals(SpeakerMatcher.Method.NEW, a.method)
        assertEquals("speaker_4", a.speakerId)
        // порядок по первому появлению: новый(4) -> speaker_1; speaker_2 -> 2; speaker_1 (старый, реплика u3) -> 3; speaker_3 -> 4
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_3", "speaker_4"), r.transcript.utterances.map { it.speakerId })
        assertEquals(mapOf("speaker_4" to "speaker_1", "speaker_2" to "speaker_2", "speaker_1" to "speaker_3", "speaker_3" to "speaker_4"), r.speakerRemap)
        assertEquals(listOf(r.transcript.utterances[0]), r.added)
        assertContract(r)
    }

    @Test
    fun `without embeddings speakers are matched by time overlap`() {
        val d = WorldDiarizer(w, mapOf(0 to 7, 1 to 3, 2 to 5, 3 to 9), withEmbeddings = false)
        val r = run(reprocessor(diar = d), FragmentRequest(5_000, 9_000, recomputeSpeakers = true))
        val a = r.speakerAssignments.single()
        assertEquals(SpeakerMatcher.Method.TIME_OVERLAP, a.method)
        assertEquals("speaker_2", a.speakerId)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1", "speaker_3"), r.transcript.utterances.map { it.speakerId })
    }

    @Test
    fun `numSpeakers=1 skips diarization but still identifies the speaker`() {
        val wrong = old.map { if (it === u3) it.copy(speakerId = "speaker_3") else it }
        val d = WorldDiarizer(w, mapOf(0 to 7, 1 to 3, 2 to 5, 3 to 9))
        val r = run(reprocessor(diar = d), FragmentRequest(10_000, 14_000, recomputeSpeakers = true, numSpeakers = 1), transcript(wrong))
        assertEquals(0, d.diarizeCalls)
        assertEquals(1, d.embeddingCalls)
        assertEquals("speaker_1", r.speakerAssignments.single().speakerId)
    }

    @Test
    fun `diarization failure degrades to old speakers with a warning`() {
        val failing = object : Diarizer {
            override fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ru.neurosteno.core.ProgressListener) = error("onnx boom")
            override fun close() = Unit
        }
        val rp = FragmentReprocessor(
            EngineProviders(vad = { vad }, asr = { l -> WorldAsr(w, l, asrLog) }, diarizer = { failing }),
            PipelineSettings(tier = "t"),
        )
        val r = run(rp, FragmentRequest(5_000, 9_000, recomputeSpeakers = true))
        assertEquals("speaker_2", r.transcript.utterances[1].speakerId)
        assertTrue(r.warnings.any { it.contains("diarization") && it.contains("boom") }, r.warnings.toString())
    }

    @Test
    fun `numSpeakers is ignored without recompute`() {
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000, numSpeakers = 2))
        assertTrue(r.warnings.any { it.contains("Число говорящих") })
        assertEquals(0, diarizer!!.diarizeCalls)
    }

    // ------------------------------------------------------------------ прогресс, отмена, ошибки

    @Test
    fun `progress is monotone and reaches 1`() {
        val seen = ArrayList<Triple<Stage, Float, Float>>()
        run(reprocessor(), FragmentRequest(5_000, 9_000, recomputeSpeakers = true), progress = { s, f, o -> synchronized(seen) { seen += Triple(s, f, o) } })
        val overall = seen.map { it.third }
        assertEquals(overall.sorted(), overall)
        assertEquals(1f, overall.last(), 1e-4f)
        assertTrue(seen.any { it.first == Stage.DIARIZATION })
    }

    @Test
    fun `asr failure is a PipelineException and the input transcript is untouched`() {
        val rp = FragmentReprocessor(
            EngineProviders(vad = { vad }, asr = { error("oom") }, diarizer = { error("not used") }),
            PipelineSettings(tier = "t"),
        )
        val e = assertThrows<PipelineException> { run(rp, FragmentRequest(5_000, 9_000)) }
        assertEquals(Stage.ASR, e.stage)
    }

    @Test
    fun `cancellation during asr propagates`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val slow = object : AsrEngine {
            override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ru.neurosteno.core.ProgressListener): List<Word> {
                started.countDown()
                repeat(200) { progress.onProgress(Stage.ASR, it / 200f); Thread.sleep(10) }
                return emptyList()
            }
            override fun close() = Unit
        }
        val rp = FragmentReprocessor(EngineProviders(vad = { vad }, asr = { slow }, diarizer = { error("x") }), PipelineSettings(tier = "t"))
        val job = async(Dispatchers.Default) { rp.reprocess(w.audio, transcript(), FragmentRequest(5_000, 9_000)) }
        withTimeout(5_000) { while (started.count > 0) kotlinx.coroutines.delay(5) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertThrows<CancellationException> { job.await() }
        Unit
    }

    @Test
    fun `padding is configurable`() {
        run(reprocessor(tuning = FragmentTuning(paddingMs = 0)), FragmentRequest(5_000, 9_000))
        assertEquals(listOf("ru@5000+4000"), asrLog)
        asrLog.clear()
        run(reprocessor(tuning = FragmentTuning(paddingMs = 1_000)), FragmentRequest(500, 3_000))
        // начало прижато к 0 (u1 [1000, 4000] расширяет отрезок до [500, 4000]), конец + 1000
        assertEquals(listOf("ru@0+5000"), asrLog)
    }

    @Test
    fun `speaker remap is consistent with the final numbering`() {
        val r = run(reprocessor(), FragmentRequest(5_000, 9_000))
        // старые идентификаторы -> итоговые для всех, кто остался
        assertEquals(mapOf("speaker_1" to "speaker_1", "speaker_2" to "speaker_2", "speaker_3" to "speaker_3"), r.speakerRemap)
        assertNotNull(r.stageMs["asr"])
        assertNotNull(r.stageMs["total"])
    }

    @Test
    fun `same fragment twice is idempotent`() {
        val r1 = run(reprocessor(), FragmentRequest(5_000, 9_000))
        val r2 = run(reprocessor(), FragmentRequest(5_000, 9_000), r1.transcript)
        assertEquals(r1.transcript.utterances, r2.transcript.utterances)
    }
}
