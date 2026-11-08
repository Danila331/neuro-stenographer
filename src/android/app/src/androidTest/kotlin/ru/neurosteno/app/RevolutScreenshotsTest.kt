package ru.neurosteno.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neurosteno.app.data.AppSettings
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.data.ThemeChoice
import ru.neurosteno.app.llm.LlmAvailability
import ru.neurosteno.app.models.DownloadUi
import ru.neurosteno.app.models.ModelSetRow
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.ui.RecorderUi
import ru.neurosteno.app.ui.TranscriptSession
import ru.neurosteno.app.ui.screens.HomeScreen
import ru.neurosteno.app.ui.screens.ModelActions
import ru.neurosteno.app.ui.screens.ModelsScreen
import ru.neurosteno.app.ui.screens.ProcessingScreen
import ru.neurosteno.app.ui.screens.RecordScreen
import ru.neurosteno.app.ui.screens.SettingsScreen
import ru.neurosteno.app.ui.screens.TranscriptScreen
import ru.neurosteno.app.ui.theme.NeuroTheme
import ru.neurosteno.core.Stage
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptMeta
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word

/** Контрольные снимки актуальной фиолетовой системы Revolut DNA. */
@RunWith(AndroidJUnit4::class)
class RevolutScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val graph by lazy { (app as NeuroApp).graph }
    private val out: File by lazy {
        File(app.getExternalFilesDir(null), "revolut-shots").apply { mkdirs() }
    }

    private val medium = ModelSetRow(
        "medium", "Medium", "Основной набор: баланс точности и скорости.", "medium",
        1_700_000_000, 0, installed = true, incomplete = false, active = true,
        builtin = true, inCatalog = true, recommended = true, download = DownloadUi.Idle,
    )
    private val lite = medium.copy(
        "lite", "Lite", "Минимальная нагрузка для слабых устройств.", "lite",
        861_000_000, 861_000_000, installed = false, active = false, builtin = false,
    )
    private val hard = medium.copy(
        "hard", "Hard", "Максимум точности для флагманов.", "hard",
        2_800_000_000, 2_800_000_000, installed = false, active = false, builtin = false, recommended = false,
    )
    private val modelActions = ModelActions({}, {}, {}, {}, {}, {})
    private val entries = listOf(
        HistoryEntry(
            "r1", "cats_c01_v0_meeting_clean.wav", 1_794_000_000_000, 58_000, 3, 19,
            "Добрый день, коллеги! Объявляю заседание в Котовице открытым.",
        ),
        HistoryEntry(
            "r2", "cats_c04_v0_call_overlap.wav", 1_793_990_000_000, 30_000, 3, 14,
            "Ставлю на голосование предложение о новой меске для общего зала.",
        ),
    )

    private fun capture(name: String, theme: ThemeChoice, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            NeuroTheme(theme) {
                Box(Modifier.fillMaxSize().testTag("shots_root")) { content() }
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onNodeWithTag("shots_root").captureToImage().asAndroidBitmap()
        File(out, "${name}_${if (theme == ThemeChoice.DARK) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun home(theme: ThemeChoice) = capture("01_home", theme) {
        HomeScreen(
            appName = "Нейро-стенографист Medium",
            entries = entries,
            models = ModelsStatus.Ready("medium"),
            activeSet = medium,
            onPickAudio = {}, onRecord = {}, onOpen = {}, onDelete = {}, onModels = {}, onSettings = {}, onSearch = {},
        )
    }

    private fun processing(theme: ThemeChoice) = capture("02_processing", theme) {
        ProcessingScreen(
            state = ProcessingUi.Running(
                sourceName = "proc_demo.wav",
                stage = Stage.ASR,
                stageFraction = 0.42f,
                overall = 0.42f,
                fractions = mapOf(Stage.DECODE to 1f, Stage.VAD to 1f, Stage.LID to 1f, Stage.ASR to 0.42f),
                audioDurationMs = 30_000,
                startedAtMs = System.currentTimeMillis() - 3_000,
            ),
            models = ModelsStatus.Ready("medium"),
            priorRtf = 0.2,
            showNotificationHint = false,
            onEnableNotifications = {}, onDismissNotificationHint = {}, onCancel = {},
        )
    }

    private fun models(theme: ThemeChoice) = capture("03_models", theme) {
        ModelsScreen(
            rows = listOf(medium, lite, hard), bundledSetId = "medium", freeBytes = 6_600_000_000,
            message = null, actions = modelActions, onDismissMessage = {}, onBack = {},
        )
    }

    private fun settings(theme: ThemeChoice) = capture("04_settings", theme) {
        SettingsScreen(
            settings = AppSettings(), configSpellsNumbers = false, activeSet = medium, tier = "medium",
            versionName = "0.1.0", historyBytes = 18_400_000, onChange = {}, onModels = {}, onStats = {},
            onLicenses = {}, onClearHistory = {}, onBack = {},
        )
    }

    private fun record(theme: ThemeChoice) = capture("05_record", theme) {
        RecordScreen(
            ui = RecorderUi(recording = false), permissionDenied = false,
            onStart = {}, onStop = {}, onOpenAppSettings = {}, onBack = {},
        )
    }

    private fun utterance(speaker: Int, start: Long, end: Long, text: String) = Utterance(
        "speaker_$speaker", start, end, text,
        text.split(' ').mapIndexed { index, word -> Word(word, start + index * 100L, start + index * 100L + 90) },
    )

    private fun wav(): ByteArray {
        val samples = ByteArray(16_000)
        val buffer = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + samples.size)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(16_000)
        buffer.putInt(32_000)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(samples.size)
        buffer.put(samples)
        return buffer.array()
    }

    private fun transcript(theme: ThemeChoice) {
        val doc = Transcript(
            listOf(
                utterance(1, 0, 3_000, "Добрый день, коллеги! Объявляю заседание в Котовице открытым."),
                utterance(2, 4_000, 7_000, "Здравствуйте, Мурзек Васильевич. Нас сегодня сколько?"),
                utterance(1, 8_000, 11_000, "По списку двенадцать котов и три кошки."),
                utterance(2, 12_000, 15_000, "А котята, которые пишут протокол встречи?"),
                utterance(3, 16_000, 20_000, "Я пушинка, а это рыжик. Мы готовы начать."),
            ),
            TranscriptMeta("medium", 58_000, 5_000, mapOf("total" to 5_000L), emptyMap(), "test"),
        )
        val entry = graph.history.add("cats_c01_v0_meeting_clean.wav", doc, audio = { ByteArrayInputStream(wav()) })
        val session = TranscriptSession(graph, entry.id)
        rule.waitUntil(5_000) { session.doc.value != null }
        capture("06_transcript", theme) {
            TranscriptScreen(
                session = session,
                llmAvailability = LlmAvailability.Available,
                onBack = {}, onExport = {}, onShare = {}, onProtocol = {}, onSummary = {}, onSearch = {},
            )
        }
        session.close()
        graph.history.delete(entry.id)
    }

    @Test fun shots_00_home_light() = home(ThemeChoice.LIGHT)
    @Test fun shots_01_home_dark() = home(ThemeChoice.DARK)
    @Test fun shots_02_processing_light() = processing(ThemeChoice.LIGHT)
    @Test fun shots_03_processing_dark() = processing(ThemeChoice.DARK)
    @Test fun shots_04_models_light() = models(ThemeChoice.LIGHT)
    @Test fun shots_05_models_dark() = models(ThemeChoice.DARK)
    @Test fun shots_06_settings_light() = settings(ThemeChoice.LIGHT)
    @Test fun shots_07_settings_dark() = settings(ThemeChoice.DARK)
    @Test fun shots_08_record_light() = record(ThemeChoice.LIGHT)
    @Test fun shots_09_record_dark() = record(ThemeChoice.DARK)
    @Test fun shots_10_transcript_light() = transcript(ThemeChoice.LIGHT)
    @Test fun shots_11_transcript_dark() = transcript(ThemeChoice.DARK)
}
