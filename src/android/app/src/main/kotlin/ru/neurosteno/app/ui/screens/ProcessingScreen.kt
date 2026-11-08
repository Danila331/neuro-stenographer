package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.ControlShape
import ru.neurosteno.app.ui.theme.MonoStyle
import ru.neurosteno.app.ui.theme.PillShape
import ru.neurosteno.app.ui.theme.PlaqueKind
import ru.neurosteno.app.ui.theme.RevolutHeroBackground
import ru.neurosteno.app.ui.theme.SheetShape
import ru.neurosteno.core.Stage

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
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = (now - state.startedAtMs).coerceAtLeast(0)
    val eta = EtaEstimator.remainingMs(elapsed, state.overall, state.audioDurationMs, priorRtf)
    val percentText = Format.percent(state.overall)
    val hintBlock: @Composable () -> Unit = {
        if (showNotificationHint) {
            BrutalPlaque(
                "Включите уведомления, чтобы видеть прогресс, когда приложение свёрнуто. Обработка идёт и без них.",
                kind = PlaqueKind.Neutral,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrutalButton(onClick = onEnableNotifications, height = 44.dp, modifier = Modifier.weight(1f)) { ButtonLabel("Включить") }
                    BrutalButton(onClick = onDismissNotificationHint, style = BrutalButtonStyle.Secondary, height = 44.dp, modifier = Modifier.weight(1f)) {
                        ButtonLabel("Не сейчас")
                    }
                }
            }
        }
    }
    val noteBlock: @Composable () -> Unit = {
        Text(
            "Обработка идёт на устройстве, без интернета. Приложение можно свернуть: процесс продолжится в фоне.",
            style = MaterialTheme.typography.bodySmall,
            color = Brutal.muted,
        )
    }
    val cancelButton: @Composable () -> Unit = {
        BrutalButton(
            onClick = onCancel,
            enabled = !state.cancelling,
            style = BrutalButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(Tags.CANCEL),
        ) { ButtonLabel("Отменить") }
    }
    val stagesBlock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Этапы", style = MaterialTheme.typography.titleMedium, color = Brutal.ink, modifier = Modifier.padding(bottom = 10.dp))
            val stages = buildList {
                addAll(VISIBLE_STAGES)
                state.fractions[Stage.DENOISE]?.let { if (it in 0.001f..0.999f) add(1, Stage.DENOISE) }
            }
            stages.forEach { stage ->
                StageRow(stage, state.fractions[stage] ?: 0f, active = state.fractions[stage]?.let { it > 0f && it < 1f } == true)
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Brutal.heroEnd)) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                ProcessingHero(state, models, percentText, eta, elapsed, Modifier.weight(0.43f).fillMaxHeight())
                Column(Modifier.weight(0.57f).fillMaxHeight().background(Brutal.bg)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        stagesBlock()
                        hintBlock()
                        noteBlock()
                    }
                    Box(Modifier.background(Brutal.surface).padding(20.dp).navigationBarsPadding()) { cancelButton() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ProcessingHero(state, models, percentText, eta, elapsed, Modifier.fillMaxWidth().heightIn(min = 328.dp))
                Column(Modifier.weight(1f).fillMaxWidth().background(Brutal.bg, SheetShape)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        stagesBlock()
                        hintBlock()
                        noteBlock()
                    }
                    Box(Modifier.background(Brutal.surface).padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) { cancelButton() }
                }
            }
        }
    }
}

@Composable
private fun ProcessingHero(
    state: ProcessingUi.Running,
    models: ModelsStatus,
    percentText: String,
    eta: Long?,
    elapsed: Long,
    modifier: Modifier = Modifier,
) {
    RevolutHeroBackground(modifier) {
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Обработка записи", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                state.sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = Brutal.heroMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (models is ModelsStatus.Installing) {
                Text("Подготовка моделей · ${Format.percent(models.fraction)}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                HeroProgress(models.fraction, "Подготовка моделей ${Format.percent(models.fraction)}")
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    percentText,
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    modifier = Modifier.semantics { contentDescription = "Общий прогресс $percentText" },
                )
                HeroProgress(state.overall, "Общий прогресс $percentText")
                Text(
                    when {
                        state.cancelling -> "Отмена…"
                        eta != null -> "Осталось примерно ${Format.duration(eta)} · прошло ${Format.duration(elapsed)}"
                        else -> "Оценка времени появится через несколько секунд · прошло ${Format.duration(elapsed)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Brutal.heroMuted,
                )
                state.audioDurationMs?.let {
                    Text("Запись ${Format.clock(it)}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun HeroProgress(fraction: Float?, description: String) {
    Box(
        Modifier.fillMaxWidth().height(7.dp).clip(PillShape).background(Color.White.copy(alpha = 0.22f))
            .semantics { contentDescription = description },
    ) {
        if (fraction != null) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Color.White, PillShape))
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
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .background(if (active) Brutal.accentSoft else Color.Transparent, ControlShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title: $status" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(if (done || active) Brutal.accentSoft else Color.Transparent, CircleShape)
                .border(1.dp, if (done || active) Brutal.accent else Brutal.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, contentDescription = null, tint = Brutal.accent, modifier = Modifier.size(18.dp))
            else if (active) Box(Modifier.size(8.dp).background(Brutal.accent, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = if (active || done) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
            color = if (active || done) Brutal.ink else Brutal.muted,
            modifier = Modifier.weight(1f),
        )
        if (active) Text(Format.percent(fraction), style = MonoStyle, color = Brutal.accent)
    }
}

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
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(Modifier.size(64.dp).background(Brutal.errorSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Brutal.error, modifier = Modifier.size(32.dp))
            }
            state.sourceName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            MessageCard(state.message, isError = true)
            BrutalButton(onClick = onPickAnother, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Выбрать другой файл") }
            if (state.message.contains("модел", ignoreCase = true)) {
                BrutalButton(onClick = onModels, style = BrutalButtonStyle.Secondary, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel("Открыть «Модели»")
                }
            }
            BrutalButton(onClick = onClose, style = BrutalButtonStyle.Secondary, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Закрыть") }
        }
    }
}
