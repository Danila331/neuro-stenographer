package ru.neurosteno.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neurosteno.app.live.LiveUi
import ru.neurosteno.app.ui.RecorderUi
import ru.neurosteno.app.ui.screens.RecordScreen
import ru.neurosteno.app.ui.theme.NeuroTheme
import ru.neurosteno.core.live.LiveState
import ru.neurosteno.core.live.LiveStatus

/** Экран «Запись» с живой расшифровкой (B13): текст, пояснения, состояния, кнопка «Остановить» всегда на месте. */
@RunWith(AndroidJUnit4::class)
class LiveRecordUiTest {
    @get:Rule val rule = createComposeRule()

    private fun show(ui: RecorderUi, live: LiveUi, onStop: () -> Unit = {}) = rule.setContent {
        NeuroTheme {
            RecordScreen(ui = ui, live = live, permissionDenied = false, onStart = {}, onStop = onStop, onOpenAppSettings = {}, onBack = {})
        }
    }

    private val recording = RecorderUi(recording = true, elapsedMs = 12_000, level = 0.4f)

    @Test
    fun liveTextShowsFinalAndPartialPhrasesWithStopButton() {
        var stopped = false
        show(
            recording,
            LiveUi(enabled = true, status = LiveStatus.Active(), state = LiveState(finals = listOf("добрый день коллеги"), partial = "начинаем")),
            onStop = { stopped = true },
        )
        rule.onNodeWithTag("live_panel").assertIsDisplayed()
        rule.onNodeWithText("Живой текст").assertIsDisplayed()
        rule.onNodeWithContentDescription("Живой текст: Добрый день коллеги начинаем").assertIsDisplayed()
        rule.onNodeWithText("Остановить и расшифровать", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("btn_stop_record").performClick()
        assertTrue(stopped)
    }

    @Test
    fun emptyAndLoadingStatesAreExplained() {
        show(recording, LiveUi(enabled = true, status = LiveStatus.Loading))
        rule.onNodeWithText("Загрузка модели…").assertIsDisplayed()
        rule.onNodeWithText("Говорите — слова появятся здесь…").assertIsDisplayed()
    }

    @Test
    fun failureDoesNotStopRecording() {
        show(recording, LiveUi(enabled = true, status = LiveStatus.Failed("нет файлов модели")))
        rule.onNodeWithTag("live_failed").assertIsDisplayed()
        rule.onNodeWithTag("btn_stop_record").assertIsDisplayed()
    }

    @Test
    fun disabledPreviewShowsReasonAndKeepsClassicScreen() {
        show(recording, LiveUi(offReason = "Живой текст выключен: мало памяти. Итог после остановки будет таким же."))
        rule.onNodeWithTag("live_off_reason").assertIsDisplayed()
        rule.onNodeWithText("Говорите естественно. Уровень микрофона показан выше — после остановки начнётся точная расшифровка.").assertIsDisplayed()
        rule.onNodeWithTag("btn_stop_record").assertIsDisplayed()
    }

    @Test
    fun beforeRecordingStartButtonIsShown() {
        show(RecorderUi(), LiveUi())
        rule.onNodeWithTag("btn_start_record").assertIsDisplayed()
        rule.onNodeWithText("Начните запись разговора. Разрешение микрофона понадобится только сейчас.").assertIsDisplayed()
    }
}
