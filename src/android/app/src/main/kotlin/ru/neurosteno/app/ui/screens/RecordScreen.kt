package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.RecorderUi
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar

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
    Scaffold(topBar = { NeuroTopBar("Запись", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Format.clock(ui.elapsedMs), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
            LinearProgressIndicator(progress = { ui.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(0.7f))
            Text(
                if (ui.recording) "Идёт запись… Не сворачивайте приложение, пока записываете." else "Нажмите кнопку, чтобы начать запись разговора.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.recording) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("btn_stop_record"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text("  Остановить и расшифровать", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 10.dp))
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("btn_start_record")) {
                    Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text("  Начать запись", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
            if (permissionDenied) {
                MessageCard("Для записи нужен доступ к микрофону. Выбор готового файла работает и без него.") {
                    OutlinedButton(onClick = onOpenAppSettings) { Text("Открыть настройки приложения") }
                }
            }
            ui.error?.let { MessageCard(it, isError = true) }
        }
    }
}
