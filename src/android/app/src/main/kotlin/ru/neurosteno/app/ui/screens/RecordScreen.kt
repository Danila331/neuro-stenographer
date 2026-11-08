package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.neurosteno.app.live.LiveUi
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.RecorderUi
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.HeroCircleButton
import ru.neurosteno.app.ui.theme.PillShape
import ru.neurosteno.app.ui.theme.RevolutHeroBackground
import ru.neurosteno.app.ui.theme.SheetShape
import ru.neurosteno.core.live.LiveStatus

@Composable
fun RecordScreen(
    ui: RecorderUi,
    permissionDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onBack: () -> Unit,
    live: LiveUi = LiveUi(),
) {
    val showLive = ui.recording && live.enabled
    BoxWithConstraints(Modifier.fillMaxSize().background(Brutal.heroEnd)) {
        val hero: @Composable (Modifier) -> Unit = { modifier -> RecordHero(ui, showLive, onBack, modifier) }
        val lower: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                if (showLive) {
                    LivePanel(live, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp))
                } else {
                    RecordIntro(ui, live, Modifier.weight(1f))
                }
                RecordActions(ui, permissionDenied, onStart, onStop, onOpenAppSettings)
            }
        }
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                hero(Modifier.weight(0.43f).fillMaxHeight())
                lower(Modifier.weight(0.57f).fillMaxHeight().background(Brutal.bg))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                hero(Modifier.fillMaxWidth().heightIn(min = if (showLive) 268.dp else 316.dp))
                lower(Modifier.weight(1f).fillMaxWidth().background(Brutal.bg, SheetShape))
            }
        }
    }
}

@Composable
private fun RecordHero(ui: RecorderUi, showLive: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    RevolutHeroBackground(modifier) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroCircleButton(onClick = onBack, contentDescription = "Назад") {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                Text("Запись", style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(44.dp))
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.background(Color.White.copy(alpha = 0.20f), PillShape).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(if (ui.recording) "Идёт запись" else "Готово к записи", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
                Text(
                    Format.clock(ui.elapsedMs),
                    style = if (showLive) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    modifier = Modifier.semantics {
                        contentDescription = (if (ui.recording) "Идёт запись: " else "Время записи: ") + Format.clock(ui.elapsedMs)
                    },
                )
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(PillShape)
                        .background(Color.White.copy(alpha = 0.22f))
                        .semantics { contentDescription = "Уровень микрофона ${(ui.level.coerceIn(0f, 1f) * 100).toInt()} процентов" },
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(ui.level.coerceIn(0f, 1f)).background(Color.White, PillShape))
                }
            }
        }
    }
}

@Composable
private fun RecordIntro(ui: RecorderUi, live: LiveUi, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(76.dp).background(Brutal.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Mic, contentDescription = null, tint = Brutal.accent, modifier = Modifier.size(35.dp))
        }
        Text(
            if (ui.recording) {
                "Говорите естественно. Уровень микрофона показан выше — после остановки начнётся точная расшифровка."
            } else {
                "Начните запись разговора. Разрешение микрофона понадобится только сейчас."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Brutal.ink,
            textAlign = TextAlign.Center,
        )
        if (ui.recording) {
            Text("Не сворачивайте приложение до окончания записи.", style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, textAlign = TextAlign.Center)
            live.offReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Brutal.muted, modifier = Modifier.testTag("live_off_reason"))
            }
        }
    }
}

@Composable
private fun RecordActions(
    ui: RecorderUi,
    permissionDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 18.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (ui.recording) {
            BrutalButton(onClick = onStop, height = 58.dp, modifier = Modifier.fillMaxWidth().testTag("btn_stop_record")) {
                Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(21.dp))
                ButtonLabel("Остановить и расшифровать", maxLines = 2, textAlign = TextAlign.Center)
            }
        } else {
            BrutalButton(onClick = onStart, height = 58.dp, modifier = Modifier.fillMaxWidth().testTag("btn_start_record")) {
                Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(22.dp))
                ButtonLabel("Начать запись")
            }
        }
        if (permissionDenied) {
            MessageCard("Для записи нужен доступ к микрофону. Выбор готового файла работает и без него.") {
                BrutalButton(onClick = onOpenAppSettings, style = BrutalButtonStyle.Secondary, height = 48.dp, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel("Открыть настройки приложения")
                }
            }
        }
        ui.error?.let { MessageCard(it, isError = true) }
    }
}

@Composable
private fun LivePanel(live: LiveUi, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val state = live.state
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(live.status) {
        while (true) {
            delay(500)
            cursorOn = !cursorOn
        }
    }
    LaunchedEffect(state) { scroll.animateScrollTo(scroll.maxValue) }
    val text = buildAnnotatedString {
        if (state.droppedPhrases > 0) withStyle(SpanStyle(color = Brutal.muted)) { append("… ") }
        withStyle(SpanStyle(color = Brutal.ink)) { append(state.finals.joinToString(" ").replaceFirstChar { it.uppercase() }) }
        if (state.partial.isNotBlank()) {
            if (state.finals.isNotEmpty()) append(" ")
            withStyle(SpanStyle(color = Brutal.muted)) {
                append(if (state.finals.isEmpty()) state.partial.replaceFirstChar { it.uppercase() } else state.partial)
            }
        }
        if (live.status is LiveStatus.Active || live.status == LiveStatus.Loading) {
            withStyle(SpanStyle(color = Brutal.accent)) { append(if (cursorOn) "▍" else " ") }
        }
    }
    BrutalCard(modifier.testTag("live_panel"), contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BrutalTag("Живой текст", fill = Brutal.accentSoft, textStyle = MaterialTheme.typography.labelMedium)
                when (val status = live.status) {
                    LiveStatus.Loading -> BrutalTag("Загрузка модели…", textStyle = MaterialTheme.typography.labelSmall)
                    is LiveStatus.Active -> if (status.lagging) BrutalTag("Не успевает", textStyle = MaterialTheme.typography.labelSmall)
                    else -> {}
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("live_scroll")) {
                if (state.isEmpty && live.status !is LiveStatus.Failed) {
                    Text("Говорите — слова появятся здесь…", style = MaterialTheme.typography.bodyLarge, color = Brutal.muted)
                } else {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("live_text").semantics { contentDescription = "Живой текст: " + state.fullText() },
                    )
                }
            }
            (live.status as? LiveStatus.Failed)?.let {
                Text(
                    "Живой текст остановлен (${it.message}). Запись продолжается — итог будет после остановки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brutal.ink,
                    modifier = Modifier.testTag("live_failed"),
                )
            }
            if ((live.status as? LiveStatus.Active)?.lagging == true) {
                Text(
                    "Распознавание не успевает за речью: в превью могут пропасть слова. В итоге они будут.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brutal.muted,
                )
            }
            Text(
                "Быстрый набросок без знаков и говорящих. Точный итог появится после остановки и заменит этот текст.",
                style = MaterialTheme.typography.bodySmall,
                color = Brutal.muted,
            )
        }
    }
}
