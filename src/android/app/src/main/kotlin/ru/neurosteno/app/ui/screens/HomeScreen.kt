package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.models.ModelSetRow
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.*
import ru.neurosteno.app.ui.theme.*

object Tags {
    const val PICK_AUDIO = "btn_pick_audio"
    const val RECORD = "btn_record"
    const val EXPORT_JSON = "btn_export_json"
    const val CANCEL = "btn_cancel_processing"
}

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
    onSearch: (() -> Unit)? = null,
) {
    var toDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    Scaffold(
        containerColor = Brutal.bg,
        topBar = {
            NeuroTopBar(appName, titleMaxLines = 2, actions = {
                if (onSearch != null) BrutalIconButton(onSearch, "AI-поиск по истории") { Icon(Icons.Default.Search, null, Modifier.size(21.dp)) }
                BrutalIconButton(onModels, "Модели") { Icon(AppIcons.Memory, null, Modifier.size(21.dp)) }
                BrutalIconButton(onSettings, "Настройки") { Icon(Icons.Default.Settings, null, Modifier.size(21.dp)) }
            })
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val actions: @Composable () -> Unit = { ActionPanel(models, activeSet, onPickAudio, onRecord, onModels) }
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(0.44f).verticalScroll(rememberScrollState()).padding(vertical = 18.dp)) { actions() }
                    LazyColumn(Modifier.weight(0.56f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)) {
                        historyItems(entries, onOpen) { toDelete = it }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
                ) {
                    item { actions() }
                    historyItems(entries, onOpen) { toDelete = it }
                }
            }
        }
    }
    toDelete?.let { e ->
        ConfirmDialog(
            "Удалить расшифровку?",
            "«${e.title}» и сохранённая копия аудио будут удалены с устройства. Экспортированные файлы не затрагиваются.",
            "Удалить", onConfirm = { onDelete(e); toDelete = null }, onDismiss = { toDelete = null }, destructive = true,
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
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrutalButton(onPickAudio, modifier = Modifier.weight(1.15f).fillMaxHeight().testTag(Tags.PICK_AUDIO), height = 52.dp, contentDescription = "Выбрать аудиофайл") {
                Icon(AppIcons.Folder, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                ButtonLabel("Импорт аудио")
            }
            BrutalButton(onRecord, modifier = Modifier.weight(1f).fillMaxHeight().testTag(Tags.RECORD), style = BrutalButtonStyle.Secondary, height = 52.dp) {
                Icon(AppIcons.Mic, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                ButtonLabel("Записать")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(AppIcons.Lock, null, Modifier.size(13.dp), tint = Brutal.muted)
            Text("Только на вашем устройстве", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
        }
        ModelsSummary(models, activeSet, onModels)
    }
}

@Composable
private fun ModelsSummary(models: ModelsStatus, activeSet: ModelSetRow?, onModels: () -> Unit) {
    when (models) {
        is ModelsStatus.Checking -> Text("Проверяем модели…", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
        is ModelsStatus.Installing -> BrutalCard(Modifier.fillMaxWidth(), fill = Brutal.panel) {
            Text("Готовим приложение · ${Format.percent(models.fraction)}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            BrutalProgress(models.fraction)
            Spacer(Modifier.height(8.dp))
            Text("Это нужно только при первом запуске. Файл можно выбрать уже сейчас.", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
        }
        is ModelsStatus.Missing -> MessageCard(models.message, isError = true) {
            BrutalButton(onModels, style = BrutalButtonStyle.Secondary) { ButtonLabel("Открыть «Модели»") }
        }
        is ModelsStatus.Ready -> Row(
            Modifier.fillMaxWidth().clickable(onClick = onModels).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(5.dp).background(Brutal.strong, RoundedCornerShape(3.dp)))
            Text("Готово к записи", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
            Spacer(Modifier.weight(1f))
            Text(activeSet?.title ?: models.setId, style = MaterialTheme.typography.labelSmall, color = Brutal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.historyItems(
    entries: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
) {
    item(key = "history-header") {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Недавние", style = MaterialTheme.typography.titleMedium, color = Brutal.ink)
            Spacer(Modifier.weight(1f))
            if (entries.isNotEmpty()) Text("${entries.size}", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
        }
    }
    if (entries.isEmpty()) item(key = "history-empty") {
        BrutalCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp).background(Brutal.panel, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Description, null, Modifier.size(23.dp), tint = Brutal.muted)
                }
                Text("Пока нет записей", style = MaterialTheme.typography.titleMedium)
                Text("Запишите разговор или добавьте аудио.\nРасшифровка появится здесь.", style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    } else items(entries, key = { it.id }) { e -> HistoryCard(e, { onOpen(e) }, { onDelete(e) }) }
}

private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")) }

@Composable
private fun HistoryCard(e: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val date = dateFormat.get()!!.format(Date(e.createdAt))
    val speakers = Format.plural(e.speakerCount, "говорящий", "говорящих", "говорящих")
    BrutalCard(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Расшифровка ${e.title}, $date, ${Format.clock(e.durationMs)}, $speakers" },
        onClick = onOpen, onClickLabel = "Открыть расшифровку", contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(Brutal.panel, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.AudioFile, null, Modifier.size(17.dp), tint = Brutal.muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$date · ${Format.clock(e.durationMs)}", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
                if (e.preview.isNotBlank()) Text(e.preview, style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(speakers, style = MaterialTheme.typography.labelSmall, color = Brutal.muted)
            }
            BrutalIconButton(onDelete, "Удалить расшифровку ${e.title}") { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) }
        }
    }
}
