package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.data.AppSettings
import ru.neurosteno.app.data.LanguageChoice
import ru.neurosteno.app.data.NumbersChoice
import ru.neurosteno.app.data.ThemeChoice
import ru.neurosteno.app.models.ModelSetRow
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.ConfirmDialog
import ru.neurosteno.app.ui.components.InfoRow
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.components.SectionCard
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalChip
import ru.neurosteno.app.ui.theme.ButtonLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    /** Что делает конфиг уровня с числами, пока пользователь не выбрал сам (`postprocess.numbers == words`). */
    configSpellsNumbers: Boolean,
    activeSet: ModelSetRow?,
    tier: String,
    versionName: String,
    historyBytes: Long,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onModels: () -> Unit,
    onStats: () -> Unit,
    onLicenses: () -> Unit = {},
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Настройки", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionCard(title = "Язык записи") {
                Text(
                    "Автоматически — русский и английский. Выберите язык вручную, если он известен.",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LanguageChoice.entries.forEach { l ->
                        BrutalChip(selected = settings.language == l, onClick = { onChange { it.copy(language = l) } }, label = l.label,
                            modifier = Modifier.testTag("lang_${l.id}"))
                    }
                }
            }
            SectionCard(title = "Числа") {
                val words = when (settings.numbers) {
                    NumbersChoice.WORDS -> true
                    NumbersChoice.DIGITS -> false
                    NumbersChoice.CONFIG -> configSpellsNumbers
                }
                Text(
                    "Как показывать числа в расшифровке и экспортируемых файлах: 25 или двадцать пять.",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrutalChip(selected = !words, onClick = { onChange { it.copy(numbers = NumbersChoice.DIGITS) } }, label = "Цифрами",
                        modifier = Modifier.testTag("numbers_digits"))
                    BrutalChip(selected = words, onClick = { onChange { it.copy(numbers = NumbersChoice.WORDS) } }, label = "Словами",
                        modifier = Modifier.testTag("numbers_words"))
                }
            }
            SectionCard(title = "Число говорящих") {
                Text("Укажите число участников, если знаете его заранее. Это поможет точнее разделить реплики.", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..AppSettings.MAX_SPEAKERS).forEach { n ->
                        BrutalChip(
                            selected = settings.numSpeakers == n, onClick = { onChange { it.copy(numSpeakers = n) } },
                            label = if (n == 0) "Авто" else n.toString(),
                            modifier = Modifier.testTag("speakers_$n"),
                        )
                    }
                }
            }
            SectionCard(title = "Тема оформления") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemeChoice.entries.forEach { t ->
                        BrutalChip(selected = settings.theme == t, onClick = { onChange { it.copy(theme = t) } }, label = t.label, modifier = Modifier.testTag("theme_${t.id}"))
                    }
                }
            }
            SectionCard(title = "Набор моделей") {
                if (activeSet != null) {
                    InfoRow("Название", activeSet.title)
                    InfoRow("Уровень", activeSet.tier.ifBlank { "—" })
                    InfoRow("Источник", if (activeSet.builtin) "встроенный в APK" else "загружен из сети")
                    InfoRow("Размер", Format.bytes(activeSet.sizeBytes))
                } else {
                    Text("Нет активного набора моделей.", style = MaterialTheme.typography.bodyMedium)
                }
                BrutalButton(onClick = onModels, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = Modifier.fillMaxWidth().testTag("btn_open_models")) { ButtonLabel("Управление моделями") }
            }
            SectionCard(title = "Диагностика") {
                BrutalButton(onClick = onStats, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = Modifier.fillMaxWidth().testTag("btn_open_stats")) { ButtonLabel("Статистика обработки") }
            }
            SectionCard(title = "Лицензии") {
                Text("Модели, библиотеки и шрифты, на которых построено приложение, и полные тексты их лицензий.", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
                BrutalButton(onClick = onLicenses, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = Modifier.fillMaxWidth().testTag("btn_open_licenses")) { ButtonLabel("Лицензии и модели") }
            }
            SectionCard(title = "Хранилище") {
                Text("Расшифровки и копии аудио хранятся только в приватной папке приложения: ${Format.bytes(historyBytes)}.", style = MaterialTheme.typography.bodyMedium)
                BrutalButton(onClick = { confirmClear = true }, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Очистить историю") }
            }
            SectionCard(title = "О приложении") {
                InfoRow("Версия", versionName)
                InfoRow("Вариант", tier)
                Text(
                    "Обработка выполняется на устройстве. Интернет используется только для скачивания моделей — по вашей кнопке в разделе «Модели».",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                    modifier = Modifier.semantics { contentDescription = "Обработка выполняется на устройстве. Интернет используется только для скачивания моделей." },
                )
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Очистить историю?", text = "Все сохранённые расшифровки и копии аудио будут удалены с устройства. Экспортированные файлы не затрагиваются.",
            confirmLabel = "Очистить", destructive = true, onConfirm = { confirmClear = false; onClearHistory() }, onDismiss = { confirmClear = false },
        )
    }
}
