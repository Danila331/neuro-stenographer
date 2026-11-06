package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.models.ModelSetRow
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.ConfirmDialog
import ru.neurosteno.app.ui.components.IconLabel
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar

object Tags {
    const val PICK_AUDIO = "btn_pick_audio"
    const val RECORD = "btn_record"
    const val EXPORT_JSON = "btn_export_json"
    const val CANCEL = "btn_cancel_processing"
}

/**
 * Главный экран: две крупные кнопки (импорт и запись), пометка об обработке на устройстве, активный набор моделей и
 * история. Никакого онбординга: файл выбирается сразу, обработка стартует сама (BACKLOG B2).
 */
@Composable
fun HomeScreen(
    appName: String,
    entries: List<HistoryEntry>,
    models: ModelsStatus,
    activeSet: ModelSetRow?,
    onPickAudio: () -> Unit,
    onRecord: () -> Unit,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
) {
    var toDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    Scaffold(
        topBar = {
            NeuroTopBar(
                title = appName,
                actions = {
                    IconButton(onClick = onModels) { Icon(AppIcons.Memory, contentDescription = "Модели") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val actionPanel: @Composable () -> Unit = {
                ActionPanel(models, activeSet, onPickAudio, onRecord, onModels)
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(0.45f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) { actionPanel() }
                    LazyColumn(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                        historyItems(entries, onOpen) { toDelete = it }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    item { actionPanel() }
                    historyItems(entries, onOpen) { toDelete = it }
                }
            }
        }
    }
    toDelete?.let { e ->
        ConfirmDialog(
            title = "Удалить расшифровку?",
            text = "«${e.title}» и сохранённая копия аудио будут удалены с устройства. Экспортированные файлы не затрагиваются.",
            confirmLabel = "Удалить",
            destructive = true,
            onConfirm = { onDelete(e); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun ActionPanel(
    models: ModelsStatus,
    activeSet: ModelSetRow?,
    onPickAudio: () -> Unit,
    onRecord: () -> Unit,
    onModels: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onPickAudio,
            modifier = Modifier.fillMaxWidth().height(72.dp).testTag(Tags.PICK_AUDIO),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(),
        ) {
            Icon(AppIcons.AudioFile, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Выбрать аудиофайл", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(
            onClick = onRecord,
            modifier = Modifier.fillMaxWidth().height(64.dp).testTag(Tags.RECORD),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text("Записать", style = MaterialTheme.typography.titleMedium)
        }
        IconLabel(AppIcons.Lock, "Обработка выполняется на устройстве", modifier = Modifier.padding(horizontal = 4.dp))
        ModelsSummary(models, activeSet, onModels)
    }
}

@Composable
private fun ModelsSummary(models: ModelsStatus, activeSet: ModelSetRow?, onModels: () -> Unit) {
    when (models) {
        is ModelsStatus.Checking -> Text("Проверка моделей…", style = MaterialTheme.typography.bodySmall)
        is ModelsStatus.Installing -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Подготовка моделей (только при первом запуске): ${Format.percent(models.fraction)}", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { models.fraction }, modifier = Modifier.fillMaxWidth())
            Text("Можно уже выбрать файл: обработка начнётся, как только модели будут готовы.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ModelsStatus.Missing -> MessageCard(models.message, isError = true) {
            OutlinedButton(onClick = onModels) { Text("Открыть «Модели»") }
        }
        is ModelsStatus.Ready -> Card(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Открыть раздел «Модели»", onClick = onModels),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(AppIcons.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Набор моделей", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        activeSet?.let { "${it.title}${if (it.builtin) " · встроенный" else " · загруженный"} · ${Format.bytes(it.sizeBytes)}" } ?: models.setId,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.historyItems(
    entries: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
) {
    item(key = "history-header") {
        Text(
            if (entries.isEmpty()) "История расшифровок" else "История расшифровок · ${entries.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (entries.isEmpty()) {
        item(key = "history-empty") {
            Text(
                "Здесь появятся ваши расшифровки: выберите аудиофайл или запишите разговор.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(entries, key = { it.id }) { e -> HistoryCard(e, onOpen = { onOpen(e) }, onDelete = { onDelete(e) }) }
    }
}

private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")) }

@Composable
private fun HistoryCard(e: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val summary = buildString {
        append(Format.clock(e.durationMs))
        append(" · ").append(Format.plural(e.speakerCount, "говорящий", "говорящих", "говорящих"))
        append(" · ").append(Format.plural(e.utteranceCount, "реплика", "реплики", "реплик"))
    }
    val date = dateFormat.get()!!.format(Date(e.createdAt))
    Card(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Расшифровка ${e.title}, $date, $summary" }
            .clickable(onClickLabel = "Открыть расшифровку", onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$date · $summary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.preview.isNotBlank()) {
                    Text(e.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Удалить расшифровку ${e.title}") }
        }
    }
}
