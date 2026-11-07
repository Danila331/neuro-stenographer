package ru.neurosteno.app.insight

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.neurosteno.app.TestData.utt

@OptIn(ExperimentalCoroutinesApi::class)
class SearchTest {
    private val rec1 = SearchRecord(
        "r1", "Совещание",
        listOf(
            utt(1, 1_000, 3_000, "добрый день коллеги начинаем"),
            utt(2, 4_000, 6_000, "насчитали сорок семь мышей на складе"),
            utt(1, 7_000, 9_000, "мыши это серьёзно нужен дежурный"),
            utt(2, 10_000, 12_000, "решили назначить дежурной Багиру"),
            utt(1, 13_000, 15_000, "переходим к следующему вопросу про сметану"),
        ),
        mapOf("speaker_2" to "Мурзик"),
    )
    private val rec2 = SearchRecord("r2", "Звонок", listOf(utt(1, 500, 2_000, "завтра привезут сметану и молоко"), utt(2, 2_500, 4_000, "хорошо договорились")))

    @Before fun before() { FakeInsightConfig.reset(); FakeInsightConfig.searchStepMs = 0 }
    @After fun after() { FakeInsightConfig.reset() }

    @Test
    fun highlightMatchesWordsByStem() {
        val text = "Насчитали сорок семь мышей на складе, мыши и Мышка"
        val r = Highlight.ranges(text, QueryTerms.of("что говорили про мышей"))
        assertEquals(listOf("мышей", "мыши", "Мышка"), r.map { text.substring(it) })
        assertTrue(Highlight.ranges(text, emptyList()).isEmpty())
        assertTrue(Highlight.ranges("ёлка", listOf("елк")).isNotEmpty()) // ё = е
    }

    @Test
    fun queryTermsDropQuestionWordsAndKeepStems() {
        assertEquals(listOf("мыш", "скла"), QueryTerms.of("Что говорил Говорящий 1 про мышей на складе?"))
        assertTrue(QueryTerms.of("что это было").isEmpty())
    }

    @Test
    fun citationParserFindsClickableReferences() {
        val text = "Говорящий 2 сообщил о мышах [00:04, Говорящий 2]. Дежурная — Багира [01:05:09, Говорящий 12]. Без ссылки [мусор]."
        val parts = CitationParser.parse(text)
        val cites = parts.filterIsInstance<CitationParser.Part.Cite>()
        assertEquals(2, cites.size)
        assertEquals(SearchCitation(4_000, "speaker_2"), cites[0].citation)
        assertEquals(SearchCitation(3_909_000, "speaker_12"), cites[1].citation)
        assertEquals(text, parts.joinToString("") { when (it) { is CitationParser.Part.Text -> it.text; is CitationParser.Part.Cite -> it.raw } })
        assertNull(CitationParser.citations("[00:04, Мурзик]").single().speakerId)
        assertTrue(CitationParser.parse("просто текст").single() is CitationParser.Part.Text)
    }

    @Test
    fun refusalIsRecognized() {
        assertTrue(CitationParser.isRefusal("В найденных фрагментах этого нет."))
        assertTrue(CitationParser.isRefusal("  В найденных фрагментах этого нет  "))
        assertFalse(CitationParser.isRefusal("Говорящий 1 сказал, что этого нет [00:01, Говорящий 1]"))
    }

    @Test
    fun citationResolvesToTheRecordOfTheMatchingFragment() = runTest {
        val repo = FakeSearchRepository { listOf(rec1, rec2) }
        val hits = repo.search("сметану", SearchScope.HISTORY, null)
        assertEquals(setOf("r1", "r2"), hits.map { it.recordId }.toSet())
        val c = SearchCitation(500, "speaker_1")
        assertEquals("r2", CitationParser.resolveRecord(c, hits, "r1"))
        assertEquals("r1", CitationParser.resolveRecord(SearchCitation(13_000, "speaker_1"), hits, "r2"))
        assertEquals("zzz", CitationParser.resolveRecord(SearchCitation(99_000, "speaker_1"), hits, "zzz"))
    }

    @Test
    fun fakeSearchFindsWindowsWithNamesAndRespectsScope() = runTest {
        val repo = FakeSearchRepository { listOf(rec1, rec2) }
        val inRecord = repo.search("что говорили про мышей", SearchScope.RECORD, "r1")
        assertTrue(inRecord.isNotEmpty())
        assertTrue(inRecord.all { it.recordId == "r1" })
        val line = inRecord.first().lines.first { it.text.contains("мышей") }
        assertEquals("Мурзик", line.speakerName) // пользовательское имя
        assertTrue(inRecord.first().terms.isNotEmpty())
        assertTrue(repo.search("сметану", SearchScope.RECORD, "r2").all { it.recordId == "r2" })
        assertTrue(repo.search("квантовая физика", SearchScope.HISTORY, null).isEmpty())
        assertTrue(repo.search("что это", SearchScope.HISTORY, null).isEmpty())
    }

    /** Область контроллера -- вне TestScope (сборщик состояния индекса бесконечен), но на том же планировщике: advanceUntilIdle её продвигает. */
    private fun controller(scope: TestScope, records: List<SearchRecord> = listOf(rec1, rec2)) =
        SearchController(FakeSearchRepository { records }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler) + kotlinx.coroutines.SupervisorJob()))

    @Test
    fun controllerSearchesThenAnswersWithCitations() = runTest {
        val c = controller(this)
        c.open("r1")
        assertEquals(SearchScope.RECORD, c.ui.value.scope)
        c.setQuery("Что говорили про мышей?")
        c.submit()
        assertEquals(SearchPhase.Searching, c.ui.value.phase)
        advanceUntilIdle()
        assertEquals(SearchPhase.Results, c.ui.value.phase)
        assertTrue(c.ui.value.hits.isNotEmpty())
        assertEquals(AnswerUi.Ready, c.ui.value.answer)
        c.answer()
        advanceUntilIdle()
        val a = c.ui.value.answer as AnswerUi.Done
        assertTrue(CitationParser.citations(a.text).isNotEmpty())
    }

    @Test
    fun controllerHandlesNoResultsRefusalAndUnavailable() = runTest {
        val c = controller(this)
        c.open("r1"); c.setQuery("квантовая физика"); c.submit(); advanceUntilIdle()
        assertEquals(SearchPhase.NoResults, c.ui.value.phase)
        assertEquals(AnswerUi.Hidden, c.ui.value.answer)

        FakeInsightConfig.searchAnswerMode = "refuse"
        c.setQuery("мыши"); c.submit(); advanceUntilIdle(); c.answer(); advanceUntilIdle()
        assertEquals(AnswerUi.Refused, c.ui.value.answer)
        assertTrue(c.ui.value.hits.isNotEmpty()) // фрагменты остаются при отказе

        FakeInsightConfig.searchAnswerMode = "unavailable"
        c.submit(); advanceUntilIdle()
        assertTrue(c.ui.value.answer is AnswerUi.Unavailable)
        c.answer(); advanceUntilIdle()
        assertTrue(c.ui.value.answer is AnswerUi.Unavailable) // повторно не запускается

        FakeInsightConfig.searchAnswerMode = "disabled"
        c.submit(); advanceUntilIdle()
        assertEquals(AnswerUi.Disabled, c.ui.value.answer)
    }

    @Test
    fun scopeSwitchRerunsSearchAndHistoryOnlyFromHome() = runTest {
        val c = controller(this)
        c.open(null)
        assertEquals(SearchScope.HISTORY, c.ui.value.scope)
        assertFalse(c.ui.value.canScopeRecord)
        c.setScope(SearchScope.RECORD)
        assertEquals(SearchScope.HISTORY, c.ui.value.scope) // без записи область «эта запись» недоступна
        c.open("r1"); c.setQuery("сметану"); c.submit(); advanceUntilIdle()
        assertEquals(setOf("r1"), c.ui.value.hits.map { it.recordId }.toSet())
        c.setScope(SearchScope.HISTORY); advanceUntilIdle()
        assertEquals(setOf("r1", "r2"), c.ui.value.hits.map { it.recordId }.toSet())
    }

    @Test
    fun indexBuildingIsReportedAndBlankQueryResets() = runTest {
        FakeInsightConfig.searchIndexBuilding = true
        val c = controller(this)
        c.open("r1"); advanceUntilIdle()
        val idx = c.ui.value.index as IndexState.Building
        assertEquals(12, idx.done)
        assertEquals(0.3f, idx.fraction, 0.001f)
        c.setQuery("   "); c.submit()
        assertEquals(SearchPhase.Idle, c.ui.value.phase)
        c.close()
    }

    @Test
    fun stopAnswerKeepsPartialText() = runTest {
        FakeInsightConfig.searchStepMs = 50
        val c = controller(this)
        c.open("r1"); c.setQuery("мышей"); c.submit(); testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        c.answer(); testScheduler.advanceTimeBy(120); testScheduler.runCurrent()
        assertTrue(c.ui.value.answer is AnswerUi.Generating)
        c.stopAnswer()
        assertTrue(c.ui.value.answer is AnswerUi.Done || c.ui.value.answer == AnswerUi.Ready)
        advanceUntilIdle()
    }

    @Test
    fun controllerShowsDuplicatedModelSentenceOnlyOnce() = runTest {
        FakeInsightConfig.searchAnswerMode = "duplicate"
        val c = controller(this)
        c.open("r1"); c.setQuery("мышей"); c.submit(); advanceUntilIdle(); c.answer(); advanceUntilIdle()
        val a = c.ui.value.answer as AnswerUi.Done
        val sentence = a.text.substringBefore(" [").trim()
        assertEquals(1, Regex(Regex.escape(sentence)).findAll(a.text).count())
        assertEquals(1, CitationParser.citations(a.text).size) // ссылка осталась у единственного экземпляра
    }
}
