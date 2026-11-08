package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalDivider
import ru.neurosteno.app.ui.theme.BrutalIconButton
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.ControlShape
import ru.neurosteno.app.ui.theme.HeroCircleButton
import ru.neurosteno.app.ui.theme.HeroQuickAction
import ru.neurosteno.app.ui.theme.RevolutHeroBackground
import ru.neurosteno.app.ui.theme.SheetShape

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
    BoxWithConstraints(Modifier.fillMaxSize().background(Brutal.bg)) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                HomeHero(
                    appName = appName,
                    onPickAudio = onPickAudio,
                    onRecord = onRecord,
                    onModels = onModels,
                    onSettings = onSettings,
                    onSearch = onSearch,
                    fillHeight = true,
                    modifier = Modifier.weight(0.43f),
                )
                LazyColumn(Modifier.weight(0.57f).fillMaxHeight().background(Brutal.bg)) {
                    item {
                        LowerContent(
                            entries = entries,
                            models = models,
                            activeSet = activeSet,
                            onModels = onModels,
                            onOpen = onOpen,
                            onDelete = { toDelete = it },
                            rounded = false,
                            modifier = Modifier.statusBarsPadding(),
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    HomeHero(
                        appName = appName,
                        onPickAudio = onPickAudio,
                        onRecord = onRecord,
                        onModels = onModels,
                        onSettings = onSettings,
                        onSearch = onSearch,
                        fillHeight = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    LowerContent(
                        entries = entries,
                        models = models,
                        activeSet = activeSet,
                        onModels = onModels,
                        onOpen = onOpen,
                        onDelete = { toDelete = it },
                        rounded = true,
                    )
                }
            }
        }
    }
    toDelete?.let { entry ->
        ConfirmDialog(
            title = "Удалить расшифровку?",
            text = "«${entry.title}» и сохранённая копия аудио будут удалены с устройства. Экспортированные файлы не затрагиваются.",
            confirmLabel = "Удалить",
            destructive = true,
            onConfirm = { onDelete(entry); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun HomeHero(
    appName: String,
    onPickAudio: () -> Unit,
    onRecord: () -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onSearch: (() -> Unit)?,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    RevolutHeroBackground(
        modifier.then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.heightIn(min = 420.dp)),
    ) {
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Mic, contentDescription = appName, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Row(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (onSearch == null) 0.13f else 0.20f))
                        .clickable(enabled = onSearch != null, role = Role.Button, onClickLabel = "AI-поиск по истории") { onSearch?.invoke() }
                        .semantics { if (onSearch == null) disabled() }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                    Text("Поиск", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                HeroCircleButton(onClick = onModels, contentDescription = "Модели") {
                    Icon(AppIcons.Memory, contentDescription = null, modifier = Modifier.size(21.dp))
                }
                HeroCircleButton(onClick = onSettings, contentDescription = "Настройки") {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp))
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    appName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text("00:00", style = MaterialTheme.typography.displayLarge, color = Color.White)
                Row(
                    Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.20f)).padding(horizontal = 15.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AppIcons.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text("Новая расшифровка", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            data class Action(
                val label: String,
                val onClick: () -> Unit,
                val icon: androidx.compose.ui.graphics.vector.ImageVector,
                val tag: String? = null,
            )
            val actions = listOf(
                Action("Выбрать аудиофайл", onPickAudio, AppIcons.Folder, Tags.PICK_AUDIO),
                Action("Записать", onRecord, AppIcons.Mic, Tags.RECORD),
                Action("Модели", onModels, AppIcons.Memory),
                if (onSearch != null) Action("AI-поиск", onSearch, Icons.Default.Search)
                else Action("Настройки", onSettings, Icons.Default.Settings),
            )
            val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
            if (largeText) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.chunked(2).forEach { rowActions ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowActions.forEach { action ->
                                HeroQuickAction(
                                    label = action.label,
                                    onClick = action.onClick,
                                    modifier = Modifier.weight(1f).let { if (action.tag == null) it else it.testTag(action.tag) },
                                ) { Icon(action.icon, contentDescription = null, modifier = Modifier.size(25.dp)) }
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    actions.forEach { action ->
                        HeroQuickAction(
                            label = action.label,
                            onClick = action.onClick,
                            modifier = Modifier.weight(1f).let { if (action.tag == null) it else it.testTag(action.tag) },
                        ) { Icon(action.icon, contentDescription = null, modifier = Modifier.size(25.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LowerContent(
    entries: List<HistoryEntry>,
    models: ModelsStatus,
    activeSet: ModelSetRow?,
    onModels: () -> Unit,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    rounded: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brutal.bg, if (rounded) SheetShape else androidx.compose.ui.graphics.RectangleShape)
            .padding(top = 16.dp, bottom = 32.dp),
    ) {
        ModelPromo(models, activeSet, onModels, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
        HistoryPanel(entries, onOpen, onDelete, Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun ModelPromo(models: ModelsStatus, activeSet: ModelSetRow?, onModels: () -> Unit, modifier: Modifier = Modifier) {
    BrutalCard(
        modifier.fillMaxWidth(),
        fill = Brutal.surface,
        onClick = onModels,
        onClickLabel = "Открыть раздел «Модели»",
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (models) {
                        is ModelsStatus.Missing -> "Подготовка к расшифровке"
                        is ModelsStatus.Installing -> "Подготовка моделей"
                        else -> "Приватно по умолчанию"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Brutal.ink,
                )
                Text(
                    when (models) {
                        is ModelsStatus.Ready -> activeSet?.let { "${it.title} · ${Format.bytes(it.sizeBytes)}" } ?: models.setId
                        is ModelsStatus.Installing -> "Установка · ${Format.percent(models.fraction)}"
                        is ModelsStatus.Checking -> "Проверяем модели на устройстве…"
                        is ModelsStatus.Missing -> models.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Brutal.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Обработка выполняется на устройстве", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
                if (models is ModelsStatus.Installing) BrutalProgress(models.fraction, Modifier.padding(top = 4.dp), height = 6.dp)
            }
            Box(Modifier.size(72.dp).background(Brutal.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Lock, contentDescription = null, tint = Brutal.accent, modifier = Modifier.size(29.dp))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(28.dp).background(Brutal.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Memory, contentDescription = null, tint = Brutal.onAccent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryPanel(
    entries: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    BrutalCard(modifier.fillMaxWidth(), fill = Brutal.surface, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("История", style = MaterialTheme.typography.titleMedium, color = Brutal.ink, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) BrutalTag("${entries.size}", textStyle = MaterialTheme.typography.labelMedium)
        }
        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(52.dp).background(Brutal.panel, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Description, contentDescription = null, tint = Brutal.accent, modifier = Modifier.size(24.dp))
                }
                Text("Пока нет расшифровок", style = MaterialTheme.typography.titleSmall, color = Brutal.ink, textAlign = TextAlign.Center)
                Text(
                    "Выберите аудиофайл или запишите разговор — результат появится здесь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brutal.muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
            entries.forEachIndexed { index, entry ->
                HistoryRow(entry, onOpen = { onOpen(entry) }, onDelete = { onDelete(entry) })
                if (index < entries.lastIndex) BrutalDivider(Modifier.padding(start = 56.dp))
            }
        }
    }
}

private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")) }

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val speakers = Format.plural(entry.speakerCount, "говорящий", "говорящих", "говорящих")
    val utterances = Format.plural(entry.utteranceCount, "реплика", "реплики", "реплик")
    val date = dateFormat.get()!!.format(Date(entry.createdAt))
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 76.dp)
            .clip(ControlShape)
            .clickable(onClickLabel = "Открыть расшифровку ${entry.title}", role = Role.Button, onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(Brutal.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Description, contentDescription = null, tint = Brutal.accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, color = Brutal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(date, style = MaterialTheme.typography.bodySmall, color = Brutal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$speakers · $utterances", style = MaterialTheme.typography.bodySmall, color = Brutal.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Format.clock(entry.durationMs), style = MaterialTheme.typography.titleSmall, color = Brutal.ink, maxLines = 1)
            BrutalIconButton(onClick = onDelete, contentDescription = "Удалить расшифровку ${entry.title}") {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Brutal.muted, modifier = Modifier.size(19.dp))
            }
        }
    }
}
