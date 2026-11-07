package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.RecorderUi
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalSurface
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.MonoFamily

/** Запись разговора с микрофона: таймер, индикатор уровня, «Остановить» -> обработка стартует сама. */
@Composable
fun RecordScreen(
    ui: RecorderUi,
    permissionDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Запись", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrutalSurface(Modifier.fillMaxWidth(), fill = if (ui.recording) Brutal.accent else Brutal.bg, shadow = 6.dp, contentColor = if (ui.recording) Brutal.onAccent else Brutal.ink) {
                Text(
                    Format.clock(ui.elapsedMs), style = MaterialTheme.typography.displayLarge.copy(fontFamily = MonoFamily),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            BrutalProgress(ui.level.coerceIn(0f, 1f), Modifier.fillMaxWidth(0.8f))
            Text(
                if (ui.recording) "Идёт запись… Не сворачивайте приложение, пока записываете." else "Нажмите кнопку, чтобы начать запись разговора.",
                style = MaterialTheme.typography.bodyLarge, color = Brutal.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (ui.recording) {
                BrutalButton(onClick = onStop, style = BrutalButtonStyle.Danger, height = 72.dp, modifier = Modifier.fillMaxWidth().testTag("btn_stop_record")) {
                    Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(26.dp))
                    Text("  Остановить и расшифровать", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                BrutalButton(onClick = onStart, height = 72.dp, modifier = Modifier.fillMaxWidth().testTag("btn_start_record")) {
                    Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(26.dp))
                    Text("  Начать запись", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (permissionDenied) {
                MessageCard("Для записи нужен доступ к микрофону. Выбор готового файла работает и без него.") {
                    BrutalButton(onClick = onOpenAppSettings, style = BrutalButtonStyle.Secondary, height = 48.dp) { ButtonLabel("Открыть настройки приложения") }
                }
            }
            ui.error?.let { MessageCard(it, isError = true) }
        }
    }
}
