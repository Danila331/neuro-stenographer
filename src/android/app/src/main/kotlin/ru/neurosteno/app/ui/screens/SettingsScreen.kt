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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import ru.neurosteno.app.data.ThemeChoice
import ru.neurosteno.app.models.ModelSetRow
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.ConfirmDialog
import ru.neurosteno.app.ui.components.InfoRow
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.components.SectionCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    activeSet: ModelSetRow?,
    tier: String,
    versionName: String,
    historyBytes: Long,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onModels: () -> Unit,
    onStats: () -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(topBar = { NeuroTopBar("Настройки", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "Язык записи") {
                Text("«Авто» определяет язык по первым секундам речи (русский или английский).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LanguageChoice.entries.forEach { l ->
                        FilterChip(selected = settings.language == l, onClick = { onChange { it.copy(language = l) } }, label = { Text(l.label) },
                            modifier = Modifier.testTag("lang_${l.id}"))
                    }
                }
            }
            SectionCard(title = "Число говорящих") {
                Text("Если оно известно заранее, диаризация работает точнее. При 1 говорящем она отключается — обработка быстрее.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..AppSettings.MAX_SPEAKERS).forEach { n ->
                        FilterChip(
                            selected = settings.numSpeakers == n, onClick = { onChange { it.copy(numSpeakers = n) } },
                            label = { Text(if (n == 0) "Авто" else n.toString()) },
                            modifier = Modifier.testTag("speakers_$n"),
                        )
                    }
                }
            }
            SectionCard(title = "Тема оформления") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemeChoice.entries.forEach { t ->
                        FilterChip(selected = settings.theme == t, onClick = { onChange { it.copy(theme = t) } }, label = { Text(t.label) }, modifier = Modifier.testTag("theme_${t.id}"))
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
                OutlinedButton(onClick = onModels, modifier = Modifier.fillMaxWidth().testTag("btn_open_models")) { Text("Управление моделями") }
            }
            SectionCard(title = "Диагностика") {
                OutlinedButton(onClick = onStats, modifier = Modifier.fillMaxWidth().testTag("btn_open_stats")) { Text("Статистика обработки") }
            }
            SectionCard(title = "Хранилище") {
                Text("Расшифровки и копии аудио хранятся только в приватной папке приложения: ${Format.bytes(historyBytes)}.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Очистить историю") }
            }
            SectionCard(title = "О приложении") {
                InfoRow("Версия", versionName)
                InfoRow("Вариант", tier)
                Text(
                    "Обработка выполняется на устройстве. Интернет используется только для скачивания моделей — по вашей кнопке в разделе «Модели».",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
