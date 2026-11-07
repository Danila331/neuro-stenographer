package ru.neurosteno.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neurosteno.app.data.ThemeChoice
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

/**
 * Снимает экраны «Выжимка» и «AI-поиск» во всех состояниях, в светлой и тёмной теме, на фейковых данных (B50/B51).
 * Не входит в обычный прогон (`@Test` помечен именем `shots_`; запуск вручную):
 * `adb shell am instrument -w -e class ru.neurosteno.app.InsightScreenshotsTest ru.neurosteno.app.medium.test/androidx.test.runner.AndroidJUnitRunner`,
 * затем `adb pull /sdcard/Android/data/ru.neurosteno.app.medium/files/insight-shots`.
 */
@RunWith(AndroidJUnit4::class)
class InsightScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    private val out: File by lazy {
        File(ApplicationProvider.getApplicationContext<Application>().getExternalFilesDir(null), "insight-shots").apply { mkdirs() }
    }

    private val quick = SummaryDoc(
        mode = "extractive",
        topics = listOf(
            SummaryTopic(
                "Заседание в Котовице", 0, 23_000,
                points = listOf(
                    SummaryPoint("speaker_1", "Добрый день, коллеги! Объявляю заседание в Котовице открытым.", listOf(SummaryRef(1, 628, "speaker_1"))),
                    SummaryPoint("speaker_2", "Здравствуйте, Мурзек Васильевич. Нас сегодня сколько?", listOf(SummaryRef(2, 4_000, "speaker_2"))),
                    SummaryPoint("speaker_3", "Я пушинка, а это рыжик.", listOf(SummaryRef(5, 14_000, "speaker_3"))),
                ),
                decisions = listOf(SummaryDecision("Заседание началось в 10:00 утра", listOf(SummaryRef(6, 17_268, "speaker_1")))),
            ),
            SummaryTopic(
                "Порядок вопросов повестки", 24_000, 58_000,
                points = listOf(
                    SummaryPoint("speaker_2", "Почему вопрос про сметану стоит только на пятом месте?", listOf(SummaryRef(12, 33_532, "speaker_2"))),
                    SummaryPoint("speaker_1", "Потому что сначала нужно решить вопрос с мышами на складе.", listOf(SummaryRef(13, 36_972, "speaker_1"))),
                ),
                decisions = listOf(SummaryDecision("Повестка утверждена", listOf(SummaryRef(15, 43_652, "speaker_1")))),
                tasks = listOf(SummaryTask("Говорящий 3", "Внести возражение в протокол", listOf(SummaryRef(18, 51_927, "speaker_3")))),
            ),
        ),
    )
    private val llm = quick.copy(
        mode = "llm", model = "qwen3-1.7b",
        overview = "Обсудили открытие заседания и порядок вопросов: решили сначала рассмотреть мышей на складе, повестка утверждена.",
        topics = quick.topics.map { t -> t.copy(points = t.points.map { it.copy(text = it.text.trimEnd('.', '!', '?')) }) },
    )
    private val names: (String) -> String = { when (it) { "speaker_1" -> "Мурзик"; "speaker_2" -> "Барсик"; else -> "Говорящий 3" } }
    private val summaryActions = SummaryActions({}, {}, {}, { _, _ -> }, {}, {})

    private fun shot(name: String, theme: ThemeChoice, content: @androidx.compose.runtime.Composable () -> Unit, scrollTag: String? = null, suffix: String = "") {
        rule.setContent { NeuroTheme(theme) { content() } }
        rule.waitForIdle()
        scrollTag?.let { tag -> rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag)); rule.waitForIdle() }
        val bmp = rule.onNodeWithTag("shots_root").captureToImage().asAndroidBitmap()
        File(out, "${name}${suffix}_${if (theme == ThemeChoice.DARK) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun wrap(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        Box(Modifier.fillMaxSize().testTag("shots_root")) { content() }
    }

    private fun hit(rid: String = "r1", title: String = "Совещание", start: Long = 33_000) = SearchHit(
        rid, title, start, start + 8_000,
        listOf(
            HitLine("speaker_2", "Барсик", start, "Почему вопрос про сметану стоит только на пятом месте?"),
            HitLine("speaker_1", "Мурзик", start + 3_000, "Потому что сначала нужно решить вопрос с мышами на складе."),
            HitLine("speaker_2", "Барсик", start + 7_000, "Мыши — значит, важнее сметаны. Не согласен."),
        ),
        score = 1f, terms = listOf("смета", "мыш"),
    )
    private val searchActions = SearchActions({}, {}, {}, {}, {}, {}, { _, _ -> })
    private val answerText = "Барсик спросил, почему вопрос про сметану стоит на пятом месте [00:33, Говорящий 2]. Мурзик ответил, что сначала нужно решить вопрос с мышами на складе [00:36, Говорящий 1]."

    private data class Case(val name: String, val scroll: String?, val content: @androidx.compose.runtime.Composable () -> Unit)

    private val cases: List<Case> by lazy {
        val res = SearchUi(query = "что говорили про сметану", recordId = "r1", phase = SearchPhase.Results, hits = listOf(hit(), hit(start = 80_000)), answer = AnswerUi.Ready)
        listOf(
            // выжимка
            Case("summary_01_quick", null) { SummaryScreen(SummaryState.Shown(quick), SummaryCaps(true), names, summaryActions) },
            Case("summary_02_working", null) { SummaryScreen(SummaryState.Shown(quick.copy(fragments = 4, fragmentsDone = 2), SummaryProgress("Фрагмент 3 из 4", 2, 4)), SummaryCaps(true), names, summaryActions) },
            Case("summary_03_llm", null) { SummaryScreen(SummaryState.Shown(llm), SummaryCaps(true), names, summaryActions) },
            Case("summary_04_llm_topics", "summary_topic_1") { SummaryScreen(SummaryState.Shown(llm), SummaryCaps(true), names, summaryActions) },
            Case("summary_05_lite_extractive", null) { SummaryScreen(SummaryState.Shown(quick), SummaryCaps(false), names, summaryActions) },
            Case("summary_06_llm_unavailable", null) { SummaryScreen(SummaryState.Shown(quick), SummaryCaps(true, "Для LLM-выжимки нужно не менее 3 ГБ ОЗУ."), names, summaryActions) },
            Case("summary_07_empty", null) { SummaryScreen(SummaryState.None, SummaryCaps(true), names, summaryActions) },
            Case("summary_08_blocked_by_protocol", null) { SummaryScreen(SummaryState.None, SummaryCaps(true), names, summaryActions, "Сейчас составляется протокол — ИИ занят. Дождитесь его окончания или остановите протокол, затем запустите выжимку.") },
            Case("summary_09_failed", null) { SummaryScreen(SummaryState.Failed("Не удалось составить выжимку: не хватило памяти.", quick), SummaryCaps(true), names, summaryActions) },
            Case("summary_10_preparing", null) { SummaryScreen(SummaryState.Preparing("Загрузка модели…"), SummaryCaps(true), names, summaryActions) },
            // поиск
            Case("search_01_idle", null) { SearchScreen(SearchUi(recordId = "r1"), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_02_results_answer_ready", null) { SearchScreen(res, searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_03_answer_generating", "answer_generating") { SearchScreen(res.copy(answer = AnswerUi.Generating("Барсик спросил, почему вопрос про сметану")), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_04_answer_done", "answer_done") { SearchScreen(res.copy(answer = AnswerUi.Done(answerText)), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_05_refused", "answer_refused") { SearchScreen(res.copy(answer = AnswerUi.Refused), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_06_ai_unavailable", "answer_unavailable") { SearchScreen(res.copy(answer = AnswerUi.Unavailable("не хватает памяти для языковой модели")), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_07_ai_disabled_lite", "answer_disabled") { SearchScreen(res.copy(answer = AnswerUi.Disabled), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_08_no_results", null) { SearchScreen(SearchUi(query = "квантовая физика", recordId = "r1", phase = SearchPhase.NoResults), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_09_index_building", null) { SearchScreen(SearchUi(query = "сметана", recordId = "r1", index = IndexState.Building(12, 40)), searchSuggestions("Мурзик"), "Совещание", searchActions) },
            Case("search_10_history_scope", null) { SearchScreen(res.copy(recordId = null, scope = SearchScope.HISTORY, hits = listOf(hit(title = "Совещание 15 ноября"), hit("r2", "Звонок в офис", 12_000))), searchSuggestions(null), null, searchActions) },
            Case("search_11_error", null) { SearchScreen(SearchUi(query = "сметана", recordId = "r1", phase = SearchPhase.Error, error = "Не удалось выполнить поиск: индекс повреждён"), searchSuggestions("Мурзик"), "Совещание", searchActions) },
        )
    }

    private fun runCase(index: Int, theme: ThemeChoice) {
        val c = cases[index]
        shot(c.name, theme, wrap(c.content), c.scroll)
    }

    // Один setContent на тест, поэтому каждое состояние в каждой теме -- отдельный тест (имена shots_NN_*).
    @Test fun shots_00_l() = runCase(0, ThemeChoice.LIGHT)
    @Test fun shots_00_d() = runCase(0, ThemeChoice.DARK)
    @Test fun shots_01_l() = runCase(1, ThemeChoice.LIGHT)
    @Test fun shots_01_d() = runCase(1, ThemeChoice.DARK)
    @Test fun shots_02_l() = runCase(2, ThemeChoice.LIGHT)
    @Test fun shots_02_d() = runCase(2, ThemeChoice.DARK)
    @Test fun shots_03_l() = runCase(3, ThemeChoice.LIGHT)
    @Test fun shots_03_d() = runCase(3, ThemeChoice.DARK)
    @Test fun shots_04_l() = runCase(4, ThemeChoice.LIGHT)
    @Test fun shots_04_d() = runCase(4, ThemeChoice.DARK)
    @Test fun shots_05_l() = runCase(5, ThemeChoice.LIGHT)
    @Test fun shots_05_d() = runCase(5, ThemeChoice.DARK)
    @Test fun shots_06_l() = runCase(6, ThemeChoice.LIGHT)
    @Test fun shots_06_d() = runCase(6, ThemeChoice.DARK)
    @Test fun shots_07_l() = runCase(7, ThemeChoice.LIGHT)
    @Test fun shots_07_d() = runCase(7, ThemeChoice.DARK)
    @Test fun shots_08_l() = runCase(8, ThemeChoice.LIGHT)
    @Test fun shots_08_d() = runCase(8, ThemeChoice.DARK)
    @Test fun shots_09_l() = runCase(9, ThemeChoice.LIGHT)
    @Test fun shots_09_d() = runCase(9, ThemeChoice.DARK)
    @Test fun shots_10_l() = runCase(10, ThemeChoice.LIGHT)
    @Test fun shots_10_d() = runCase(10, ThemeChoice.DARK)
    @Test fun shots_11_l() = runCase(11, ThemeChoice.LIGHT)
    @Test fun shots_11_d() = runCase(11, ThemeChoice.DARK)
    @Test fun shots_12_l() = runCase(12, ThemeChoice.LIGHT)
    @Test fun shots_12_d() = runCase(12, ThemeChoice.DARK)
    @Test fun shots_13_l() = runCase(13, ThemeChoice.LIGHT)
    @Test fun shots_13_d() = runCase(13, ThemeChoice.DARK)
    @Test fun shots_14_l() = runCase(14, ThemeChoice.LIGHT)
    @Test fun shots_14_d() = runCase(14, ThemeChoice.DARK)
    @Test fun shots_15_l() = runCase(15, ThemeChoice.LIGHT)
    @Test fun shots_15_d() = runCase(15, ThemeChoice.DARK)
    @Test fun shots_16_l() = runCase(16, ThemeChoice.LIGHT)
    @Test fun shots_16_d() = runCase(16, ThemeChoice.DARK)
    @Test fun shots_17_l() = runCase(17, ThemeChoice.LIGHT)
    @Test fun shots_17_d() = runCase(17, ThemeChoice.DARK)
    @Test fun shots_18_l() = runCase(18, ThemeChoice.LIGHT)
    @Test fun shots_18_d() = runCase(18, ThemeChoice.DARK)
    @Test fun shots_19_l() = runCase(19, ThemeChoice.LIGHT)
    @Test fun shots_19_d() = runCase(19, ThemeChoice.DARK)
    @Test fun shots_20_l() = runCase(20, ThemeChoice.LIGHT)
    @Test fun shots_20_d() = runCase(20, ThemeChoice.DARK)
}
