package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import ru.neurosteno.app.ui.theme.BrutalDivider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.ui.EtaEstimator
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalDims
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalShape
import ru.neurosteno.app.ui.theme.BrutalSurface
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.MonoStyle
import ru.neurosteno.app.ui.theme.PlaqueKind
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
    val headerBlock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(state.sourceName, style = MaterialTheme.typography.titleMedium, maxLines = 2, color = Brutal.ink)
            if (models is ModelsStatus.Installing) {
                BrutalPlaque("Подготовка моделей (только при первом запуске): ${Format.percent(models.fraction)}. Обработка начнётся сразу после неё.", kind = PlaqueKind.Neutral) {
                    BrutalProgress(models.fraction)
                }
            }
            // огромный процент на акцентной заливке
            BrutalSurface(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Общий прогресс $percentText" },
                fill = Brutal.surface, contentColor = Brutal.ink,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(percentText, style = MaterialTheme.typography.displayLarge, color = Brutal.ink)
                    BrutalProgress(state.overall)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        state.cancelling -> "Отмена…"
                        eta != null -> "Осталось примерно ${Format.duration(eta)} · прошло ${Format.duration(elapsed)}"
                        else -> "Оценка оставшегося времени появится через несколько секунд · прошло ${Format.duration(elapsed)}"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Brutal.ink,
                )
                state.audioDurationMs?.let { BrutalTag("Запись ${Format.clock(it)}") }
            }
        }
    }
    val stagesBlock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val stages = buildList {
                addAll(VISIBLE_STAGES)
                state.fractions[Stage.DENOISE]?.let { if (it in 0.001f..0.999f) add(1, Stage.DENOISE) }
            }
            stages.forEach { st -> StageRow(st, state.fractions[st] ?: 0f, active = state.fractions[st]?.let { it > 0f && it < 1f } == true) }
        }
    }
    val hintBlock: @Composable () -> Unit = {
        if (showNotificationHint) {
            BrutalPlaque("Включите уведомления, чтобы видеть прогресс, когда приложение свёрнуто. Обработка идёт и без них.", kind = PlaqueKind.Neutral) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrutalButton(onClick = onEnableNotifications, height = 44.dp, modifier = Modifier.weight(1f)) { ButtonLabel("Включить") }
                    BrutalButton(onClick = onDismissNotificationHint, style = BrutalButtonStyle.Secondary, height = 44.dp, modifier = Modifier.weight(1f)) { ButtonLabel("Не сейчас") }
                }
            }
        }
    }
    // «Отменить» всегда на виду (в портрете закреплена внизу, а не уезжает под список стадий)
    val cancelButton: @Composable () -> Unit = {
        BrutalButton(
            onClick = onCancel, enabled = !state.cancelling, style = BrutalButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(Tags.CANCEL),
        ) { ButtonLabel("Отменить") }
    }
    val noteBlock: @Composable () -> Unit = {
        Text(
            "Обработка идёт на устройстве, без интернета. Приложение можно свернуть: процесс продолжится в фоне.",
            style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
        )
    }
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Обработка записи") }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            if (maxWidth >= 720.dp) {
                // альбомная ориентация: слева общий прогресс и «Отменить», справа стадии -- всё видно без прокрутки
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        headerBlock(); hintBlock(); cancelButton(); noteBlock()
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { stagesBlock() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        headerBlock(); stagesBlock(); hintBlock(); noteBlock()
                    }
                    BrutalDivider()
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).navigationBarsPadding()) { cancelButton() }
                }
            }
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
    val fill = if (active) Brutal.accent else Brutal.bg
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp)
            .background(fill, BrutalShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title: $status" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).background(if (done) Brutal.accent else Brutal.panel, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, contentDescription = null, tint = Brutal.strong, modifier = Modifier.size(15.dp))
            else if (active) Box(Modifier.size(6.dp).background(Brutal.strong, androidx.compose.foundation.shape.CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title, style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (active) Brutal.onAccent else if (done) Brutal.ink else Brutal.muted, modifier = Modifier.weight(1f),
        )
        if (active) BrutalTag(Format.percent(fraction), fill = Brutal.ink)
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
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Не удалось обработать", onBack = onClose) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrutalSurface(Modifier.size(72.dp), fill = Brutal.error, contentColor = Brutal.onAccent) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(44.dp)) }
            }
            state.sourceName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            MessageCard(state.message, isError = true)
            BrutalButton(onClick = onPickAnother, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Выбрать другой файл") }
            if (state.message.contains("модел", ignoreCase = true)) {
                BrutalButton(onClick = onModels, style = BrutalButtonStyle.Secondary, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Открыть «Модели»") }
            }
            BrutalButton(onClick = onClose, style = BrutalButtonStyle.Secondary, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Закрыть") }
        }
    }
}
