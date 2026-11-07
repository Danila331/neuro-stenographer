package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import ru.neurosteno.app.live.LiveUi
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.RecorderUi
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalSurface
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.core.live.LiveStatus
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.MonoFamily

/**
 * Запись разговора с микрофона: таймер, индикатор уровня, «Остановить» -> обработка стартует сама. Если доступна живая
 * расшифровка (B13) -- под таймером панель с текстом по мере речи (превью); итог после остановки даёт полная обработка.
 */
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
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Запись", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Верхняя часть: таймер и уровень; при живом тексте компактнее, чтобы оставить место под текст
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = if (showLive) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (showLive) 10.dp else 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val timerFill = if (ui.recording) Brutal.accent else Brutal.bg
                BrutalSurface(Modifier.fillMaxWidth(), fill = timerFill, shadow = 6.dp, contentColor = if (ui.recording) Brutal.onAccent else Brutal.ink) {
                    Text(
                        Format.clock(ui.elapsedMs),
                        style = (if (showLive) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge).copy(fontFamily = MonoFamily),
                        modifier = Modifier.fillMaxWidth().padding(vertical = if (showLive) 10.dp else 24.dp).semantics { contentDescription = "Идёт запись: ${Format.clock(ui.elapsedMs)}" },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                BrutalProgress(ui.level.coerceIn(0f, 1f), Modifier.fillMaxWidth(0.8f))
            }
            if (showLive) {
                LivePanel(live, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (ui.recording) "Идёт запись… Не сворачивайте приложение, пока записываете." else "Нажмите кнопку, чтобы начать запись разговора.",
                        style = MaterialTheme.typography.bodyLarge, color = Brutal.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (ui.recording) live.offReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.testTag("live_off_reason")) }
                }
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (ui.recording) {
                    BrutalButton(onClick = onStop, style = BrutalButtonStyle.Danger, height = 72.dp, modifier = Modifier.fillMaxWidth().testTag("btn_stop_record")) {
                        Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(26.dp))
                        Text("  Остановить и расшифровать", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    BrutalButton(onClick = onStart, height = 72.dp, modifier = Modifier.fillMaxWidth().testTag("btn_start_record")) {
                        Icon(AppIcons.Mic, contentDescription = null, modifier = Modifier.size(26.dp))
                        Text("  Начать запись", style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (permissionDenied) {
                    MessageCard("Для записи нужен доступ к микрофону. Выбор готового файла работает и без него.") {
                        BrutalButton(onClick = onOpenAppSettings, style = BrutalButtonStyle.Secondary, height = 48.dp, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Открыть настройки приложения") }
                    }
                }
                ui.error?.let { MessageCard(it, isError = true) }
            }
        }
    }
}

/** Живой текст: завершённые фразы -- обычным начертанием, текущая гипотеза -- серым с курсором; всегда прокручен к концу. */
@Composable
private fun LivePanel(live: LiveUi, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val st = live.state
    // мигающий курсор у текущей гипотезы
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(live.status) { while (true) { delay(500); cursorOn = !cursorOn } }
    LaunchedEffect(st) { scroll.animateScrollTo(scroll.maxValue) }
    val text = buildAnnotatedString {
        if (st.droppedPhrases > 0) withStyle(SpanStyle(color = Brutal.muted)) { append("… ") }
        withStyle(SpanStyle(color = Brutal.ink)) { append(st.finals.joinToString(" ").replaceFirstChar { it.uppercase() }) }
        if (st.partial.isNotBlank()) {
            if (st.finals.isNotEmpty()) append(" ")
            withStyle(SpanStyle(color = Brutal.muted)) { append(if (st.finals.isEmpty()) st.partial.replaceFirstChar { it.uppercase() } else st.partial) }
        }
        if (live.status is LiveStatus.Active || live.status == LiveStatus.Loading) withStyle(SpanStyle(color = Brutal.ink)) { append(if (cursorOn) "\u258D" else " ") }
    }
    BrutalCard(modifier.testTag("live_panel"), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BrutalTag("ЖИВОЙ ТЕКСТ · ПРЕВЬЮ", fill = Brutal.accent)
                when (val s = live.status) {
                    LiveStatus.Loading -> BrutalTag("ЗАГРУЗКА МОДЕЛИ…")
                    is LiveStatus.Active -> if (s.lagging) BrutalTag("НЕ УСПЕВАЕТ")
                    else -> {}
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("live_scroll")) {
                if (st.isEmpty && live.status !is LiveStatus.Failed) {
                    Text("Говорите — слова появятся здесь…", style = MaterialTheme.typography.bodyLarge, color = Brutal.muted)
                } else {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("live_text").semantics { contentDescription = "Живой текст: " + st.fullText() })
                }
            }
            (live.status as? LiveStatus.Failed)?.let {
                Text("Живой текст остановлен (${it.message}). Запись продолжается — итог будет после остановки.", style = MaterialTheme.typography.bodySmall, color = Brutal.ink, modifier = Modifier.testTag("live_failed"))
            }
            if ((live.status as? LiveStatus.Active)?.lagging == true) {
                Text("Распознавание не успевает за речью: в превью могут пропасть слова. В итоге они будут.", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
            }
            Text(
                "Это быстрый набросок без знаков и говорящих. Точный итог появится после остановки — он заменит этот текст.",
                style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
            )
        }
    }
}
