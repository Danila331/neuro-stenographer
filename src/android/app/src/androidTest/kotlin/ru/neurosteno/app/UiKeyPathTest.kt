package ru.neurosteno.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.export.ExportFormat
import ru.neurosteno.app.llm.LlmAvailability
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.ui.NeuroTheme
import ru.neurosteno.app.ui.TranscriptSession
import ru.neurosteno.app.ui.screens.HomeScreen
import ru.neurosteno.app.ui.screens.ProcessingScreen
import ru.neurosteno.app.ui.screens.TranscriptScreen
import ru.neurosteno.core.Stage
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptMeta
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word

/**
 * Compose UI-тесты ключевого пути: главный экран -> обработка -> расшифровка (поиск, переименование говорящего, правка,
 * объединение) -> «Экспорт JSON». Запуск: `./gradlew :app:connectedMediumDebugAndroidTest` (эмулятор поднят).
 */
@RunWith(AndroidJUnit4::class)
class UiKeyPathTest {
    @get:Rule val rule = createComposeRule()

    private val graph by lazy { (ApplicationProvider.getApplicationContext<Application>() as NeuroApp).graph }
    private var entryId: String? = null

    private fun utt(sp: Int, s: Long, e: Long, text: String) =
        Utterance("speaker_$sp", s, e, text, text.split(' ').mapIndexed { i, w -> Word(w, s + i * 100L, s + i * 100L + 90) })

    @Before fun seed() {
        val tr = Transcript(
            listOf(
                utt(1, 1_000, 3_000, "добрый день коллеги"),
                utt(2, 3_500, 6_000, "здравствуйте начнём совещание"),
                utt(1, 6_500, 9_000, "у нас двенадцать котов"),
            ),
            TranscriptMeta("medium", 20_000, 6_000, mapOf("total" to 6_000L), emptyMap(), "test"),
        )
        entryId = graph.history.add("ui-test.wav", tr).id
    }

    @After fun cleanup() {
        entryId?.let { graph.history.delete(it) }
    }

    @Test
    fun homeShowsMainActionsWithoutOnboarding() {
        var picked = 0
        var recorded = 0
        rule.setContent {
            NeuroTheme {
                HomeScreen(
                    appName = "Нейро-стенографист", entries = emptyList<HistoryEntry>(), models = ModelsStatus.Ready("medium"), activeSet = null,
                    onPickAudio = { picked++ }, onRecord = { recorded++ }, onOpen = {}, onDelete = {}, onModels = {}, onSettings = {},
                )
            }
        }
        rule.onNodeWithText("Выбрать аудиофайл").assertIsDisplayed().performClick()
        rule.onNodeWithText("Записать").assertIsDisplayed().performClick()
        rule.onNodeWithText("Обработка выполняется на устройстве").assertIsDisplayed()
        assertEquals(1, picked)
        assertEquals(1, recorded)
    }

    @Test
    fun processingShowsStagesPercentAndCancel() {
        var cancelled = false
        rule.setContent {
            NeuroTheme {
                ProcessingScreen(
                    state = ProcessingUi.Running(
                        sourceName = "dialog.wav", stage = Stage.ASR, stageFraction = 0.5f, overall = 0.4f,
                        fractions = mapOf(Stage.DECODE to 1f, Stage.VAD to 1f, Stage.LID to 1f, Stage.ASR to 0.5f),
                        audioDurationMs = 60_000,
                    ),
                    models = ModelsStatus.Ready("medium"), priorRtf = 0.4, showNotificationHint = true,
                    onEnableNotifications = {}, onDismissNotificationHint = {}, onCancel = { cancelled = true },
                )
            }
        }
        listOf("Декодирование", "Поиск речи", "Определение языка", "Распознавание", "Разделение говорящих", "Сборка реплик", "Пунктуация")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("40%").assertIsDisplayed()
        rule.onNodeWithText("Отменить").assertIsDisplayed().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun transcriptSearchRenameEditMergeAndExport() {
        val session = TranscriptSession(graph, entryId!!)
        var exported: ExportFormat? = null
        rule.setContent {
            NeuroTheme {
                TranscriptScreen(
                    session = session, llmAvailability = LlmAvailability.Unavailable("тест"),
                    onBack = {}, onExport = { exported = it }, onShare = {}, onProtocol = {},
                )
            }
        }
        rule.waitUntil(5_000) { session.doc.value != null }
        rule.onNodeWithText("добрый день коллеги").assertIsDisplayed()

        // поиск по тексту (кириллица, без учёта регистра)
        rule.onNodeWithContentDescription("Поиск по тексту").performClick()
        rule.onNodeWithTag("search_field").performTextInput("КОТ")
        rule.onNodeWithText("1 из 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Закрыть поиск").performClick()

        // переименование говорящего
        rule.onNodeWithContentDescription("Говорящие").performClick()
        rule.onNodeWithTag("speaker_name_speaker_1").performTextInput("Мурзик")
        rule.onNodeWithText("Готово").performClick()
        rule.waitUntil(3_000) { session.doc.value!!.transcript.displayName("speaker_1") == "Мурзик" }
        rule.onAllNodesWithContentDescription("Мурзик, 00:01", substring = true)[0].assertIsDisplayed()

        // правка текста первой реплики
        rule.onAllNodes(hasContentDescription("Действия с репликой", substring = true))[0].performClick()
        rule.onNodeWithText("Править текст").performClick()
        rule.onNodeWithTag("edit_text_field").performTextReplacement("Добрый день, коллеги!")
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitUntil(3_000) { session.doc.value!!.transcript.utterances[0].text == "Добрый день, коллеги!" }

        // объединение с соседней
        rule.onAllNodes(hasContentDescription("Действия с репликой", substring = true))[0].performClick()
        rule.onNodeWithText("Объединить со следующей").performClick()
        rule.waitUntil(3_000) { session.doc.value!!.transcript.utterances.size == 2 }
        assertEquals("Добрый день, коллеги! здравствуйте начнём совещание", session.doc.value!!.transcript.utterances[0].text)

        // экспорт JSON -- кнопка стабильная и видимая
        rule.onNode(hasText("Экспорт JSON")).assertIsDisplayed().performClick()
        assertEquals(ExportFormat.JSON, exported)
        // JSON по ТЗ после правок: только speaker_N, без имён
        val json = ru.neurosteno.app.export.Exports.render(ExportFormat.JSON, session.doc.value!!.transcript, session.doc.value!!.source)
        assertTrue(json, json.startsWith("{\"transcript\":[{\"speaker_id\":\"speaker_1\""))
        assertTrue(json, !json.contains("Мурзик"))
        session.close()
    }
}
