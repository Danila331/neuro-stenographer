package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.ui.EtaEstimator
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.core.Stage

/** Стадии, которые показываются пользователю (шумоподавление добавляется, только если действительно запускалось). */
private val VISIBLE_STAGES = listOf(Stage.DECODE, Stage.VAD, Stage.LID, Stage.ASR, Stage.DIARIZATION, Stage.ALIGN, Stage.PUNCTUATION)

@Composable
fun ProcessingScreen(
    state: ProcessingUi.Running,
    models: ModelsStatus,
    priorRtf: Double,
    showNotificationHint: Boolean,
    onEnableNotifications: () -> Unit,
    onDismissNotificationHint: () -> Unit,
    onCancel: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1_000) }
    }
    val elapsed = (now - state.startedAtMs).coerceAtLeast(0)
    val eta = EtaEstimator.remainingMs(elapsed, state.overall, state.audioDurationMs, priorRtf)
    val percentText = Format.percent(state.overall)
    Scaffold(topBar = { NeuroTopBar("Обработка записи") }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.sourceName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            if (models is ModelsStatus.Installing) {
                MessageCard("Подготовка моделей (только при первом запуске): ${Format.percent(models.fraction)}. Обработка начнётся сразу после неё.") {
                    LinearProgressIndicator(progress = { models.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            Column(
                Modifier.semantics(mergeDescendants = true) { contentDescription = "Общий прогресс $percentText" },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(percentText, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { state.overall }, modifier = Modifier.fillMaxWidth())
                Text(
                    when {
                        state.cancelling -> "Отмена…"
                        eta != null -> "Осталось примерно ${Format.duration(eta)} · прошло ${Format.duration(elapsed)}"
                        else -> "Оценка оставшегося времени появится через несколько секунд · прошло ${Format.duration(elapsed)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.audioDurationMs?.let {
                    Text("Длительность записи ${Format.clock(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val stages = buildList {
                    addAll(VISIBLE_STAGES)
                    state.fractions[Stage.DENOISE]?.let { if (it in 0.001f..0.999f) add(1, Stage.DENOISE) }
                }
                stages.forEach { st -> StageRow(st, state.fractions[st] ?: 0f, active = state.fractions[st]?.let { it > 0f && it < 1f } == true) }
            }
            if (showNotificationHint) {
                MessageCard("Включите уведомления, чтобы видеть прогресс, когда приложение свёрнуто. Обработка идёт и без них.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onEnableNotifications) { Text("Включить") }
                        TextButton(onClick = onDismissNotificationHint) { Text("Не сейчас") }
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
            OutlinedButton(onClick = onCancel, enabled = !state.cancelling, modifier = Modifier.fillMaxWidth().testTag(Tags.CANCEL)) {
                Text("Отменить")
            }
            Text(
                "Обработка идёт на устройстве, без интернета. Приложение можно свернуть: процесс продолжится в фоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StageRow(stage: Stage, fraction: Float, active: Boolean) {
    val done = fraction >= 1f
    val title = Format.stageTitle(stage)
    val status = when {
        done -> "готово"
        active -> Format.percent(fraction)
        else -> "ожидание"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "$title: $status" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                active -> CircularProgressIndicator(progress = { fraction.coerceAtLeast(0.05f) }, modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                else -> androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(Color.Gray.copy(alpha = 0.5f)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (!done && !active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (done) "" else if (active) Format.percent(fraction) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ошибка обработки: понятное сообщение и что делать дальше. */
@Composable
fun FailureScreen(
    state: ProcessingUi.Failed,
    onClose: () -> Unit,
    onPickAnother: () -> Unit,
    onModels: () -> Unit,
) {
    Scaffold(topBar = { NeuroTopBar("Не удалось обработать", onBack = onClose) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
            state.sourceName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            MessageCard(state.message, isError = true)
            Button(onClick = onPickAnother, modifier = Modifier.fillMaxWidth()) { Text("Выбрать другой файл") }
            if (state.message.contains("модел", ignoreCase = true)) {
                OutlinedButton(onClick = onModels, modifier = Modifier.fillMaxWidth()) { Text("Открыть «Модели»") }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
        }
    }
}
