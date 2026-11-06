package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.models.DownloadUi
import ru.neurosteno.app.models.ModelSetRow
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.ConfirmDialog
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar

/** Действия над наборами моделей (общие для экрана «Модели» и первого запуска варианта online). */
class ModelActions(
    val download: (String) -> Unit,
    val cancel: (String) -> Unit,
    val discardPartial: (String) -> Unit,
    val activate: (String) -> Unit,
    val delete: (String) -> Unit,
    val restoreBuiltin: () -> Unit,
)

@Composable
fun ModelsScreen(
    rows: List<ModelSetRow>,
    bundledSetId: String?,
    freeBytes: Long,
    message: String?,
    actions: ModelActions,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { NeuroTopBar("Модели", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Все вычисления выполняются на устройстве. Интернет нужен только для скачивания наборов моделей — и только по вашей кнопке. " +
                        "Свободно на устройстве: ${Format.bytes(freeBytes)}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message != null) item { MessageCard(message, isError = true) { TextButton(onClick = onDismissMessage) { Text("Понятно") } } }
            if (bundledSetId != null && rows.none { it.id == bundledSetId && it.installed }) {
                item {
                    MessageCard("Встроенный набор «$bundledSetId» сейчас удалён из хранилища приложения. Его можно восстановить из APK без интернета.") {
                        Button(onClick = actions.restoreBuiltin, modifier = Modifier.testTag("btn_restore_builtin")) { Text("Восстановить из APK") }
                    }
                }
            }
            items(rows, key = { it.id }) { row -> ModelSetCard(row, bundledSetId, actions) }
            if (rows.isEmpty()) item { Text("Наборов моделей нет.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun ModelSetCard(row: ModelSetRow, bundledSetId: String?, actions: ModelActions, primaryDownload: Boolean = false) {
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isBundled = row.id == bundledSetId
    Card(
        Modifier.fillMaxWidth().semantics(mergeDescendants = false) { contentDescription = "Набор моделей ${row.title}" },
        colors = CardDefaults.cardColors(
            containerColor = if (row.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (row.active) AssistChip(onClick = {}, label = { Text("Активный") })
                else if (row.recommended && !row.installed) AssistChip(onClick = {}, label = { Text("Рекомендуем") })
            }
            if (row.description.isNotBlank()) Text(row.description, style = MaterialTheme.typography.bodyMedium)
            Text(statusLine(row, isBundled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (val d = row.download) {
                is DownloadUi.Running -> {
                    val frac = if (d.totalBytes > 0) (d.doneBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Загрузка набора ${row.title}: ${Format.percent(frac)}" })
                    val eta = if (d.speedBps > 0) " · осталось ~${Format.duration((d.totalBytes - d.doneBytes) * 1000 / d.speedBps)}" else ""
                    val speed = if (d.speedBps > 0) " · ${Format.bytes(d.speedBps)}/с" else ""
                    val phase = if (d.file.startsWith("#")) d.file.drop(1) + "… " else ""
                    Text("$phase" + "Загружено ${Format.bytes(d.doneBytes)} из ${Format.bytes(d.totalBytes)} (${Format.percent(frac)})$speed$eta", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { actions.cancel(row.id) }) { Text("Отменить") }
                }
                DownloadUi.Queued -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Ожидание сети или запуска…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { actions.cancel(row.id) }) { Text("Отменить") }
                }
                is DownloadUi.Paused -> {
                    Text("Загрузка приостановлена: на устройстве уже ${Format.bytes(d.partialBytes)}. Можно продолжить с этого места.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.download(row.id) }) { Text("Продолжить") }
                        OutlinedButton(onClick = { actions.discardPartial(row.id) }) { Text("Удалить загруженное") }
                    }
                }
                is DownloadUi.Failed -> {
                    MessageCard(d.message, isError = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.download(row.id) }) { Text("Повторить") }
                        if (d.partialBytes > 0) OutlinedButton(onClick = { actions.discardPartial(row.id) }) { Text("Сбросить") }
                    }
                }
                DownloadUi.Idle -> IdleActions(row, isBundled, primaryDownload, actions, onAskDownload = { confirmDownload = true }, onAskDelete = { confirmDelete = true })
            }
        }
    }
    if (confirmDownload) {
        ConfirmDialog(
            title = "Скачать «${row.title}»?",
            text = "Будет загружено около ${Format.bytes(row.downloadBytes)} из официальных источников (Hugging Face, GitHub). Лучше делать это по Wi-Fi. " +
                "Загрузку можно отменить и продолжить позже. После скачивания набор можно сделать активным.",
            confirmLabel = "Скачать",
            onConfirm = { confirmDownload = false; actions.download(row.id) },
            onDismiss = { confirmDownload = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = if (isBundled) "Удалить встроенный набор?" else "Удалить набор «${row.title}»?",
            text = if (isBundled) {
                "Файлы набора будут удалены из хранилища приложения (освободится ${Format.bytes(row.sizeBytes)}). " +
                    "В самом APK они остаются, поэтому размер приложения не уменьшится — зато набор можно в любой момент восстановить из APK без интернета."
            } else {
                "Файлы набора будут удалены с устройства (освободится ${Format.bytes(row.sizeBytes)}). Его можно скачать снова."
            },
            confirmLabel = "Удалить",
            destructive = true,
            onConfirm = { confirmDelete = false; actions.delete(row.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun IdleActions(
    row: ModelSetRow,
    isBundled: Boolean,
    primaryDownload: Boolean,
    actions: ModelActions,
    onAskDownload: () -> Unit,
    onAskDelete: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (row.installed) {
            if (!row.active) Button(onClick = { actions.activate(row.id) }, modifier = Modifier.testTag("btn_activate_${row.id}")) { Text("Сделать активным") }
            OutlinedButton(onClick = onAskDelete, modifier = Modifier.testTag("btn_delete_${row.id}")) {
                Text(if (isBundled) "Удалить встроенный набор" else "Удалить")
            }
        } else {
            if (isBundled) Button(onClick = actions.restoreBuiltin) { Text("Восстановить из APK") }
            if (row.inCatalog && row.downloadBytes > 0) {
                val label = "Скачать (${Format.bytes(row.downloadBytes)})"
                if (primaryDownload && !isBundled) Button(onClick = onAskDownload, modifier = Modifier.testTag("btn_download_${row.id}")) { Text(label) }
                else OutlinedButton(onClick = onAskDownload, modifier = Modifier.testTag("btn_download_${row.id}")) { Text(label) }
            }
            if (row.incomplete && !row.inCatalog) OutlinedButton(onClick = onAskDelete) { Text("Удалить") }
        }
    }
}

private fun statusLine(row: ModelSetRow, isBundled: Boolean): String = buildString {
    when {
        row.installed -> append(if (isBundled || row.builtin) "Установлен · встроенный · " else "Установлен · загруженный · ").append(Format.bytes(row.sizeBytes))
        row.incomplete -> append("Файлы набора повреждены или неполны")
        else -> append("Не установлен")
    }
    if (row.tier.isNotBlank()) append(" · уровень ").append(row.tier)
}
