package ru.neurosteno.app.insight

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.neurosteno.app.TestData

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("insight/$name")!!.readText()

    @Before fun before() { FakeInsightConfig.reset(); FakeInsightConfig.summaryStepMs = 100 }
    @After fun after() { FakeInsightConfig.reset() }

    @Test
    fun parsesLlmSampleFromBenchRagWithoutSchemaFields() {
        val d = SummaryJson.parse(res("summary_llm_sample.json"))!!
        assertTrue(d.topics.isNotEmpty())
        assertEquals("extractive", d.mode) // у старых образцов mode нет: по умолчанию быстрая версия
        assertTrue(d.done)
        val t = d.topics.first()
        assertTrue(t.title.isNotBlank() && t.endMs > t.startMs)
        t.points.forEach { assertTrue(it.speaker.startsWith("speaker_") && it.refs.isNotEmpty() && it.refs.all { r -> r.n >= 1 && r.t >= 0 }) }
        assertTrue(d.topics.any { it.tasks.any { task -> task.who.isNotBlank() } })
    }

    @Test
    fun parsesExtractiveSampleAndRoundTrips() {
        val d = SummaryJson.parse(res("summary_extractive_sample.json"))!!
        assertTrue(d.topics.size >= 3)
        assertEquals("", d.overview)
        val again = SummaryJson.parse(SummaryJson.encode(d.copy(mode = "llm", done = false, fragments = 5, fragmentsDone = 2)))!!
        assertTrue(again.isLlm)
        assertFalse(again.done)
        assertEquals(2, again.fragmentsDone)
        assertEquals(d.topics, again.topics)
    }

    @Test
    fun schemaWithAllFieldsAndBrokenInputs() {
        val json = """{"schema":1,"mode":"llm","model":"qwen","done":false,"fragments":4,"fragments_done":1,"overview":"О мышах",
            "topics":[{"title":"Мыши","start_ms":1000,"end_ms":9000,"points":[{"speaker":"speaker_2","text":"Насчитали 47","refs":[{"n":21,"t":67200,"speaker":"speaker_2"}]}],
            "decisions":[{"text":"Назначили Багиру","refs":[{"n":37,"t":99500,"speaker":"speaker_1"}]}],"tasks":[{"who":"Говорящий 2","text":"отчёт к утру","refs":[]}],"extra":1}],"unknown":true}"""
        val d = SummaryJson.parse(json)!!
        assertEquals("О мышах", d.overview)
        assertEquals(67_200, d.topics[0].points[0].refs[0].t)
        assertEquals("Говорящий 2", d.topics[0].tasks[0].who)
        assertEquals(1, d.fragmentsDone)
        assertNull(SummaryJson.parse("не json"))
        assertNull(SummaryJson.parse("""{"topics":"x"}"""))
    }

    @Test
    fun firstRefIsEarliest() {
        assertEquals(10, listOf(SummaryRef(3, 30, "speaker_1"), SummaryRef(1, 10, "speaker_1")).firstRef()!!.t)
        assertNull(emptyList<SummaryRef>().firstRef())
    }

    private val utts = TestData.dialog().utterances + (1..9).map { TestData.utt(it % 2 + 1, 10_000L + it * 2_000, 11_500L + it * 2_000, "Нужно подготовить отчёт про мышей на складе номер $it, решили утвердить до пятницы") }

    @Test
    fun fakeBuilderProducesValidReferences() {
        val d = FakeSummaryBuilder.extractive(utts)
        assertEquals("extractive", d.mode)
        assertTrue(d.topics.size >= 2)
        val all = d.topics.flatMap { t -> t.points.flatMap { it.refs } + t.decisions.flatMap { it.refs } + t.tasks.flatMap { it.refs } }
        assertTrue(all.isNotEmpty())
        all.forEach { r ->
            assertTrue(r.n in 1..utts.size)
            assertEquals(utts[r.n - 1].startMs, r.t)         // время всегда из реплики
            assertEquals(utts[r.n - 1].speakerId, r.speaker)
        }
        d.topics.forEach { assertTrue(it.endMs >= it.startMs) }
        assertTrue(d.topics.any { it.decisions.isNotEmpty() })
        val llm = FakeSummaryBuilder.llm(d) { "Имя $it" }
        assertTrue(llm.isLlm && llm.done && llm.overview.isNotBlank())
        assertEquals(d.topics.size, llm.topics.size)
        assertTrue(llm.topics.flatMap { it.tasks }.all { it.who.startsWith("Имя ") })
    }

    @Test
    fun fakeRepositoryShowsQuickThenReplacesWithLlm() = runTest {
        val repo = FakeSummaryRepository(this)
        assertEquals(SummaryState.None, repo.state("a").value)
        repo.build("a", utts, useLlm = true)
        val first = repo.state("a").value as SummaryState.Shown
        assertEquals("extractive", first.doc.mode)
        assertTrue(first.working)
        assertEquals(0, first.doc.fragmentsDone)
        advanceUntilIdle()
        val done = repo.state("a").value as SummaryState.Shown
        assertTrue(done.doc.isLlm && !done.working)
        assertTrue(done.doc.overview.isNotBlank())
    }

    @Test
    fun extractiveModeNeverRunsLlm() = runTest {
        FakeInsightConfig.summaryLlmMode = false
        val repo = FakeSummaryRepository(this)
        repo.build("a", utts, useLlm = true)
        advanceUntilIdle()
        val s = repo.state("a").value as SummaryState.Shown
        assertEquals("extractive", s.doc.mode)
        assertTrue(s.doc.done)
        assertFalse(repo.caps().canRunLlm)
    }

    @Test
    fun unavailableLlmKeepsQuickVersion() = runTest {
        FakeInsightConfig.summaryLlmReason = "мало памяти"
        val repo = FakeSummaryRepository(this)
        assertEquals("мало памяти", repo.caps().llmUnavailableReason)
        repo.build("a", utts, useLlm = true)
        advanceUntilIdle()
        assertTrue((repo.state("a").value as SummaryState.Shown).doc.done)
        assertFalse((repo.state("a").value as SummaryState.Shown).doc.isLlm)
    }

    @Test
    fun rebuildRestartsAndCancelKeepsQuick() = runTest {
        val repo = FakeSummaryRepository(this)
        repo.build("a", utts, useLlm = true)
        repo.build("a", utts, useLlm = true) // «Пересоздать» во время работы
        repo.cancel("a")
        advanceUntilIdle()
        val s = repo.state("a").value as SummaryState.Shown
        assertTrue(s.doc.done && !s.doc.isLlm)
    }

    @Test
    fun emptyTranscriptFails() = runTest {
        val repo = FakeSummaryRepository(this)
        repo.build("a", emptyList(), useLlm = true)
        assertTrue(repo.state("a").value is SummaryState.Failed)
    }
}
