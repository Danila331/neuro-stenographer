package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.ConfirmDialog
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalIconButton
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.PlaqueKind
import ru.neurosteno.app.ui.theme.SectionTitle

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
    /** «AI-поиск» по всей истории (B51); `null` -- нет движка поиска или история пуста. */
    onSearch: (() -> Unit)? = null,
) {
    var toDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    Scaffold(
        containerColor = Brutal.bg,
        topBar = {
            NeuroTopBar(
                title = appName, titleMaxLines = 2,
                actions = {
                    if (onSearch != null) BrutalIconButton(onClick = onSearch, contentDescription = "AI-поиск по истории") { Icon(Icons.Default.Search, contentDescription = null) }
                    BrutalIconButton(onClick = onModels, contentDescription = "Модели") { Icon(AppIcons.Memory, contentDescription = null) }
                    BrutalIconButton(onClick = onSettings, contentDescription = "Настройки") { Icon(Icons.Default.Settings, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val actionPanel: @Composable () -> Unit = { ActionPanel(models, activeSet, onPickAudio, onRecord, onModels) }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(0.45f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { actionPanel() }
                    LazyColumn(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                        historyItems(entries, onOpen) { toDelete = it }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BrutalButton(
            onClick = onPickAudio, height = 92.dp,
            modifier = Modifier.fillMaxWidth().testTag(Tags.PICK_AUDIO),
        ) {
            Icon(AppIcons.AudioFile, contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text("Выбрать аудиофайл", style = MaterialTheme.typography.titleLarge, color = androidx.compose.material3.LocalContentColor.current)
        }
        BrutalButton(
            onClick = onRecord, style = BrutalButtonStyle.Secondary, height = 72.dp,
            modifier = Modifier.fillMaxWidth().testTag(Tags.RECORD),
        ) {
            Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Записать", style = MaterialTheme.typography.titleLarge, color = androidx.compose.material3.LocalContentColor.current)
        }
        BrutalPlaque("Обработка выполняется на устройстве", kind = PlaqueKind.Ink) {}
        ModelsSummary(models, activeSet, onModels)
    }
}

@Composable
private fun ModelsSummary(models: ModelsStatus, activeSet: ModelSetRow?, onModels: () -> Unit) {
    when (models) {
        is ModelsStatus.Checking -> BrutalTag("ПРОВЕРКА МОДЕЛЕЙ…")
        is ModelsStatus.Installing -> BrutalCard(Modifier.fillMaxWidth(), fill = Brutal.panel) {
            Text("Подготовка моделей (только при первом запуске): ${Format.percent(models.fraction)}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            BrutalProgress(models.fraction)
            Spacer(Modifier.height(8.dp))
            Text("Можно уже выбрать файл: обработка начнётся, как только модели будут готовы.", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
        }
        is ModelsStatus.Missing -> MessageCard(models.message, isError = true) {
            BrutalButton(onClick = onModels, style = BrutalButtonStyle.Secondary, height = 48.dp) { Text("Открыть «Модели»", style = MaterialTheme.typography.labelLarge) }
        }
        is ModelsStatus.Ready -> BrutalCard(Modifier.fillMaxWidth(), onClick = onModels, onClickLabel = "Открыть раздел «Модели»") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(AppIcons.Memory, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrutalTag("НАБОР МОДЕЛЕЙ")
                    Text(
                        activeSet?.let { "${it.title}${if (it.builtin) " · встроенный" else " · загруженный"} · ${Format.bytes(it.sizeBytes)}" } ?: models.setId,
                        style = MaterialTheme.typography.titleSmall,
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
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("История")
            if (entries.isNotEmpty()) BrutalTag("${entries.size}")
        }
    }
    if (entries.isEmpty()) {
        item(key = "history-empty") {
            Text(
                "Здесь появятся ваши расшифровки: выберите аудиофайл или запишите разговор.",
                style = MaterialTheme.typography.bodyLarge, color = Brutal.muted,
            )
        }
    } else {
        items(entries, key = { it.id }) { e -> HistoryCard(e, onOpen = { onOpen(e) }, onDelete = { onDelete(e) }) }
    }
}

private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")) }

@Composable
private fun HistoryCard(e: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val speakers = Format.plural(e.speakerCount, "говорящий", "говорящих", "говорящих")
    val utterances = Format.plural(e.utteranceCount, "реплика", "реплики", "реплик")
    val date = dateFormat.get()!!.format(Date(e.createdAt))
    BrutalCard(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Расшифровка ${e.title}, $date, ${Format.clock(e.durationMs)}, $speakers, $utterances" },
        onClick = onOpen, onClickLabel = "Открыть расшифровку",
        contentPadding = PaddingValues(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(date, style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrutalTag(Format.clock(e.durationMs), fill = Brutal.accent)
                    BrutalTag(speakers)
                    BrutalTag(utterances)
                }
                if (e.preview.isNotBlank()) {
                    Text(e.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            BrutalIconButton(onClick = onDelete, contentDescription = "Удалить расшифровку ${e.title}") { Icon(Icons.Default.Delete, contentDescription = null) }
        }
    }
}
