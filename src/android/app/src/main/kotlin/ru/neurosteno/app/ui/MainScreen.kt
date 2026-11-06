package ru.neurosteno.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.neurosteno.app.ProcessingState
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.R
import ru.neurosteno.app.models.ModelsStatus

/**
 * Экран-заглушка (волна 2 заменит полноценным UI): выбор файла -> «Обработать» -> результат -> «Экспорт JSON».
 * Подписи кнопок стабильны (BACKLOG B2: удобно для UI-автоматизации).
 */
@Composable
fun MainScreen(
    tier: String,
    pickedName: String?,
    onPick: () -> Unit,
    onProcess: () -> Unit,
    onCancel: () -> Unit,
    onExport: (suggestedName: String) -> Unit,
) {
    val models by ProcessingState.models.collectAsStateWithLifecycle()
    val state by ProcessingState.processing.collectAsStateWithLifecycle()
    val modelsReady = models is ModelsStatus.Ready
    val busy = state is ProcessingUi.Running

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.label_on_device), style = MaterialTheme.typography.bodySmall)

            when (val m = models) {
                ModelsStatus.Checking -> Text("Проверка моделей…")
                is ModelsStatus.Installing -> {
                    Text("Подготовка моделей: ${Format.percent(m.fraction)}")
                    LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth())
                }
                is ModelsStatus.Ready -> Text("Модели готовы (набор ${m.setId}, вариант $tier)")
                is ModelsStatus.Missing -> Text(m.message, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, enabled = !busy) { Text(stringResource(R.string.btn_pick_file)) }
                Button(onClick = onProcess, enabled = modelsReady && pickedName != null && !busy) {
                    Text(stringResource(R.string.btn_process))
                }
                if (busy) OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.btn_cancel)) }
            }
            Text(pickedName ?: stringResource(R.string.status_no_file), style = MaterialTheme.typography.bodyMedium)

            when (val s = state) {
                ProcessingUi.Idle -> Unit
                is ProcessingUi.Running -> {
                    Text("${Format.stageTitle(s.stage)} — ${Format.percent(s.overall)}")
                    LinearProgressIndicator(progress = { s.overall }, modifier = Modifier.fillMaxWidth())
                }
                is ProcessingUi.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is ProcessingUi.Done -> {
                    val items = s.result.transcript.utterances
                    Button(onClick = { onExport(Format.exportFileName(s.sourceName)) }) {
                        Text(stringResource(R.string.btn_export_json))
                    }
                    if (s.result.warnings.isNotEmpty()) {
                        Text("Предупреждения: ${s.result.warnings.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "RTF ${"%.2f".format(java.util.Locale.ROOT, s.result.transcript.meta.rtf)}, реплик: ${items.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(items) { u ->
                            Text(
                                "[${Format.clock(u.startMs)}] ${u.speakerId}: ${u.text}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
