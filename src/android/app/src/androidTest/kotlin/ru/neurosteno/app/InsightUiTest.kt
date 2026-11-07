package ru.neurosteno.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neurosteno.app.insight.AnswerUi
import ru.neurosteno.app.insight.HitLine
import ru.neurosteno.app.insight.IndexState
import ru.neurosteno.app.insight.SearchHit
import ru.neurosteno.app.insight.SearchPhase
import ru.neurosteno.app.insight.SearchScope
import ru.neurosteno.app.insight.SearchUi
import ru.neurosteno.app.insight.SummaryCaps
import ru.neurosteno.app.insight.SummaryProgress
import ru.neurosteno.app.insight.SummaryState
import ru.neurosteno.app.ui.screens.SearchActions
import ru.neurosteno.app.ui.screens.SearchScreen
import ru.neurosteno.app.ui.screens.SummaryActions
import ru.neurosteno.app.ui.screens.SummaryScreen
import ru.neurosteno.app.ui.screens.searchSuggestions
import ru.neurosteno.app.ui.theme.NeuroTheme
import ru.neurosteno.core.summary.SummaryDecision
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryPoint
import ru.neurosteno.core.summary.SummaryRef
import ru.neurosteno.core.summary.SummaryTask
import ru.neurosteno.core.summary.SummaryTopic

/** UI «Выжимка» (B50) и «AI-поиск» (B51) на фейковых данных: все состояния, тапы по пунктам и ссылкам, пояснения. */
@RunWith(AndroidJUnit4::class)
class InsightUiTest {
    @get:Rule val rule = createComposeRule()

    /** Элементы LazyColumn вне экрана не скомпонованы: сначала прокручиваем до нужного тега. */
    private fun scrolled(tag: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag)).let { rule.onNodeWithTag(tag) }

    // ---------------------------------------------------------------- выжимка
    private val quick = SummaryDoc(
        mode = "extractive",
        topics = listOf(
            SummaryTopic(
                "Мыши на складе", 52_000, 118_000,
                points = listOf(
                    SummaryPoint("speaker_2", "Насчитали 47 мышей", listOf(SummaryRef(21, 67_200, "speaker_2"))),
                    SummaryPoint("speaker_1", "Нужен дежурный", listOf(SummaryRef(25, 80_000, "speaker_1"))),
                ),
                decisions = listOf(SummaryDecision("Дежурной назначили Багиру", listOf(SummaryRef(37, 99_500, "speaker_1")))),
                tasks = listOf(SummaryTask("Говорящий 2", "составить отчёт к утру", listOf(SummaryRef(40, 104_000, "speaker_2")))),
            ),
            SummaryTopic("Сметана", 118_000, 150_000, points = listOf(SummaryPoint("speaker_1", "Привезут завтра", listOf(SummaryRef(50, 120_000, "speaker_1"))))),
        ),
    )
    private val llm = quick.copy(mode = "llm", overview = "Обсудили мышей на складе и закупку сметаны; дежурной назначили Багиру.")
    private val names: (String) -> String = { if (it == "speaker_2") "Мурзик" else "Говорящий 1" }
    private val jumps = ArrayList<Pair<Long, Int?>>()
    private var built = 0
    private var cancelled = 0
    private var txt = 0
    private var pdf = 0

    private fun actions() = SummaryActions(
        onBack = {}, onBuild = { built++ }, onCancel = { cancelled++ }, onJump = { t, n -> jumps += t to n },
        onExportTxt = { txt++ }, onExportPdf = { pdf++ },
    )

    private fun showSummary(state: SummaryState, caps: SummaryCaps = SummaryCaps(true), blocked: String? = null) = rule.setContent {
        NeuroTheme { SummaryScreen(state, caps, names, actions(), blocked) }
    }

    @Test
    fun quickSummaryShowsTagDraftNoticeTopicsAndJumpsToReplies() {
        showSummary(SummaryState.Shown(quick))
        rule.onNodeWithTag("summary_tag_quick").assertIsDisplayed()
        rule.onNodeWithTag("summary_draft_notice").assertIsDisplayed()
        rule.onNodeWithText("Мыши на складе").assertIsDisplayed()
        rule.onNodeWithText("00:52–01:58").assertIsDisplayed()
        rule.onNodeWithText("Мурзик").assertIsDisplayed() // имя говорящего у пункта
        rule.onNodeWithTag("summary_point_0_0").performClick()
        assertEquals(67_200L to 21, jumps.last())
        rule.onNodeWithTag("summary_decision_0_0").performClick()
        assertEquals(99_500L to 37, jumps.last())
        rule.onNodeWithTag("summary_task_0_0").performClick()
        assertEquals(104_000L to 40, jumps.last())
        rule.onNodeWithText("Говорящий 2").assertIsDisplayed() // исполнитель задачи
        rule.onNodeWithText("Мыши на складе").performClick()   // заголовок темы -- к началу темы
        assertEquals(52_000L to null, jumps.last())
    }

    @Test
    fun workingStateShowsProgressAndCancel() {
        showSummary(SummaryState.Shown(quick.copy(fragments = 4, fragmentsDone = 2), SummaryProgress("Фрагмент 3 из 4", 2, 4)))
        rule.onNodeWithTag("summary_working").assertIsDisplayed()
        rule.onNodeWithText("ИИ-выжимка готовится…").assertIsDisplayed()
        rule.onNodeWithTag("summary_tag_quick").assertIsDisplayed() // пока показана быстрая версия
        rule.onNodeWithTag("btn_summary_cancel").performClick()
        assertEquals(1, cancelled)
        rule.onNodeWithTag("btn_summary_rebuild").assertDoesNotExist() // «Пересоздать» недоступно, пока идёт работа
    }

    @Test
    fun llmSummaryShowsOverviewTagAndRebuildAndExports() {
        showSummary(SummaryState.Shown(llm))
        rule.onNodeWithTag("summary_tag_llm").assertIsDisplayed()
        rule.onNodeWithTag("summary_overview").assertIsDisplayed()
        rule.onNodeWithText("Обсудили мышей на складе и закупку сметаны; дежурной назначили Багиру.").assertIsDisplayed()
        rule.onNodeWithTag("summary_working").assertDoesNotExist()
        scrolled("btn_summary_rebuild").performClick()
        assertEquals(1, built)
        scrolled("btn_summary_txt").performClick()
        scrolled("btn_summary_pdf").performClick()
        assertEquals(1, txt); assertEquals(1, pdf)
    }

    @Test
    fun liteExtractiveModeExplainsNoAi() {
        showSummary(SummaryState.Shown(quick), SummaryCaps(llmMode = false))
        rule.onNodeWithTag("summary_mode_note").assertIsDisplayed()
        rule.onNodeWithText("В этом варианте приложения выжимка строится без ИИ: темы и цитаты реплик по говорящим.").assertIsDisplayed()
    }

    @Test
    fun unavailableLlmKeepsQuickVersionWithReason() {
        showSummary(SummaryState.Shown(quick), SummaryCaps(llmMode = true, llmUnavailableReason = "Для LLM-выжимки нужно не менее 3 ГБ ОЗУ."))
        rule.onNodeWithTag("summary_mode_note").assertIsDisplayed()
        rule.onNodeWithText("ИИ-выжимка недоступна: Для LLM-выжимки нужно не менее 3 ГБ ОЗУ. Показана быстрая версия.", substring = true).assertIsDisplayed()
    }

    @Test
    fun emptyStateOffersToBuildAndRespectsBlockedByProtocol() {
        showSummary(SummaryState.None)
        rule.onNodeWithTag("summary_empty").assertIsDisplayed()
        rule.onNodeWithTag("btn_make_summary").assertIsEnabled().performClick()
        assertEquals(1, built)
    }

    @Test
    fun blockedByProtocolGenerationDisablesBuildWithMessage() {
        showSummary(SummaryState.None, blocked = "Сейчас составляется протокол — ИИ занят.")
        rule.onNodeWithTag("summary_blocked").assertIsDisplayed()
        rule.onNodeWithTag("btn_make_summary").assertIsNotEnabled()
    }

    @Test
    fun blockedDisablesRebuildOnExistingSummary() {
        showSummary(SummaryState.Shown(llm), blocked = "Сейчас составляется протокол — ИИ занят.")
        scrolled("btn_summary_rebuild").assertIsNotEnabled()
        scrolled("btn_summary_pdf").assertIsEnabled() // экспорт при этом работает
    }

    @Test
    fun failedStateShowsMessageAndRetry() {
        showSummary(SummaryState.Failed("Не удалось составить выжимку: нет памяти", quick))
        rule.onNodeWithText("Не удалось составить выжимку: нет памяти").assertIsDisplayed()
        scrolled("btn_summary_rebuild").performClick()
        assertEquals(1, built)
    }

    @Test
    fun preparingShowsStage() {
        showSummary(SummaryState.Preparing("Загрузка модели…"))
        rule.onNodeWithTag("summary_working").assertIsDisplayed()
        rule.onNodeWithText("Загрузка модели…", substring = true).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- поиск
    private fun hit(rid: String = "r1", title: String = "Совещание") = SearchHit(
        rid, title, 4_000, 12_000,
        listOf(
            HitLine("speaker_2", "Мурзик", 4_000, "Насчитали сорок семь мышей на складе"),
            HitLine("speaker_1", "Говорящий 1", 8_000, "Мыши это серьёзно, нужен дежурный"),
        ),
        score = 1f, terms = listOf("мыш"),
    )

    private val opened = ArrayList<Pair<String, Long>>()
    private val queries = ArrayList<String>()
    private val scopes = ArrayList<SearchScope>()
    private var submitted = 0
    private var answered = 0
    private var stopped = 0

    private fun searchActions() = SearchActions(
        onBack = {}, onQuery = { queries += it }, onSubmit = { submitted++ }, onScope = { scopes += it },
        onAnswer = { answered++ }, onStopAnswer = { stopped++ }, onOpen = { r, ms -> opened += r to ms },
    )

    private fun showSearch(ui: SearchUi, title: String? = "Совещание") = rule.setContent {
        NeuroTheme { SearchScreen(ui, searchSuggestions("Мурзик"), title, searchActions()) }
    }

    private val resultsUi get() = SearchUi(query = "мыши", recordId = "r1", phase = SearchPhase.Results, hits = listOf(hit()), answer = AnswerUi.Ready)

    @Test
    fun idleShowsSuggestionsScopeAndStartsSearch() {
        showSearch(SearchUi(recordId = "r1"))
        rule.onNodeWithTag("search_query").assertIsDisplayed()
        rule.onNodeWithTag("scope_record").assertIsDisplayed()
        rule.onNodeWithTag("scope_history").performClick()
        assertEquals(listOf(SearchScope.HISTORY), scopes)
        rule.onNodeWithTag("suggestion_0").performClick()
        assertEquals("Что говорил Мурзик про ", queries.last())
        rule.onNodeWithTag("suggestion_1").performClick()
        assertEquals("Что обсуждали по теме ", queries.last())
        rule.onNodeWithTag("btn_search_go").assertIsNotEnabled() // пустой запрос
    }

    @Test
    fun fromHomeOnlyHistoryScopeIsOffered() {
        showSearch(SearchUi(recordId = null, scope = SearchScope.HISTORY), title = null)
        rule.onNodeWithTag("scope_history").assertIsDisplayed()
        rule.onNodeWithTag("scope_record").assertDoesNotExist()
    }

    @Test
    fun resultsShowHitsWithSpeakersTimeAndOpenTheReplyOnTap() {
        showSearch(resultsUi)
        rule.onNodeWithTag("search_hit_0").assertIsDisplayed()
        rule.onNodeWithTag("search_hits_count").assertIsDisplayed()
        rule.onNodeWithText("00:04–00:12").assertIsDisplayed()
        rule.onNodeWithText("Мурзик · 00:04").assertIsDisplayed()
        rule.onNodeWithTag("search_hit_0_line_1").performClick()
        assertEquals("r1" to 8_000L, opened.last())
        rule.onNodeWithText("00:04–00:12").performClick() // заголовок фрагмента -- к его началу
        assertEquals("r1" to 4_000L, opened.last())
        // ответ предлагается только после фрагментов
        scrolled("btn_answer").performClick()
        assertEquals(1, answered)
    }

    @Test
    fun historyScopeShowsRecordTitleOnHits() {
        showSearch(resultsUi.copy(scope = SearchScope.HISTORY, hits = listOf(hit("r2", "Звонок в офис"))))
        rule.onNodeWithText("Звонок в офис").assertIsDisplayed()
    }

    @Test
    fun citationsInAnswerAreClickableAndResolveToTheRecord() {
        val text = "Мурзик сказал: «Насчитали сорок семь мышей» [00:04, Говорящий 2]. Ещё: «нужен дежурный» [00:08, Говорящий 1]."
        showSearch(resultsUi.copy(answer = AnswerUi.Done(text), hits = listOf(hit("r7", "Совещание"))))
        rule.onNodeWithTag("search_query").assertIsDisplayed()
        scrolled("answer_done")
        val node = rule.onNodeWithTag("answer_text")
        val layouts = ArrayList<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        fun clickAt(token: String) {
            val box = layouts[0].getBoundingBox(text.indexOf(token) + 2)
            node.performTouchInput { click(box.center) }
        }
        clickAt("[00:04")
        assertEquals("r7" to 4_000L, opened.last())
        clickAt("[00:08")
        assertEquals("r7" to 8_000L, opened.last())
    }

    @Test
    fun answerStates() {
        showSearch(resultsUi.copy(answer = AnswerUi.Generating("Мурзик сказал")))
        rule.onNodeWithTag("answer_generating").assertIsDisplayed()
        scrolled("btn_answer_stop").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun refusalShowsNoAnswerButKeepsFragments() {
        showSearch(resultsUi.copy(answer = AnswerUi.Refused))
        rule.onNodeWithTag("answer_refused").assertIsDisplayed()
        rule.onNodeWithText("В записи нет ответа").assertIsDisplayed()
        rule.onNodeWithTag("search_hit_0").assertIsDisplayed()
    }

    @Test
    fun unavailableAndDisabledAiShowOnlyFragmentsWithExplanation() {
        showSearch(resultsUi.copy(answer = AnswerUi.Unavailable("не хватает памяти для языковой модели")))
        rule.onNodeWithTag("answer_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("btn_answer").assertDoesNotExist()
    }

    @Test
    fun disabledAnswerInLite() {
        showSearch(resultsUi.copy(answer = AnswerUi.Disabled))
        rule.onNodeWithTag("answer_disabled").assertIsDisplayed()
    }

    @Test
    fun noResultsAndIndexBuildingStates() {
        showSearch(SearchUi(query = "квант", recordId = "r1", phase = SearchPhase.NoResults, index = IndexState.Building(12, 40)))
        rule.onNodeWithTag("search_no_results").assertIsDisplayed()
        rule.onNodeWithTag("search_index_building").assertIsDisplayed()
        rule.onNodeWithText("Ничего не найдено").assertIsDisplayed()
    }

    @Test
    fun unavailableIndexBlocksSearch() {
        showSearch(SearchUi(query = "мыши", recordId = "r1", index = IndexState.Unavailable("нет модели эмбеддингов")))
        rule.onNodeWithTag("search_index_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("btn_search_go").assertIsNotEnabled()
    }
}
