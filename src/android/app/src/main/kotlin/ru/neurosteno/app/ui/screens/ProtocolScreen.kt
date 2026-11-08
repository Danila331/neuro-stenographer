package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.llm.LlmAvailability
import ru.neurosteno.app.llm.LlmController
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalChip
import ru.neurosteno.app.ui.theme.BrutalIconButton
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.BrutalTextField
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.PlaqueKind

private val SUGGESTIONS = listOf("О чём разговор?", "Какие решения приняты?", "Кому какие задачи поставлены?")

/** Подпись для `llm.quality = draft`. */
const val DRAFT_NOTICE = "Черновой протокол — упрощённая модель"

/**
 * «Протокол заседания» и «Вопрос по расшифровке» -- локальная LLM, запускается по кнопке после расшифровки
 * (не входит в основной путь). Недоступность (нет модели, мало памяти, нет dotprod) объясняется текстом.
 */
@Composable
fun ProtocolScreen(
    llm: LlmController,
    transcript: EditableTranscript?,
    title: String,
    onBack: () -> Unit,
    onShareText: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val ui by llm.ui.collectAsState()
    val availability = remember(ui.running) { llm.availability() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Протокол и вопросы", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrutalChip(selected = tab == 0, onClick = { tab = 0 }, label = "Протокол заседания", modifier = Modifier.weight(1f).testTag("tab_protocol"))
                BrutalChip(selected = tab == 1, onClick = { tab = 1 }, label = "Вопрос по расшифровке", modifier = Modifier.weight(1f).testTag("tab_qa"))
            }
            if (availability is LlmAvailability.Unavailable) {
                MessageCard(availability.reason, isError = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            if (transcript == null) {
                Text("Расшифровка загружается…", Modifier.padding(16.dp))
            } else if (tab == 0) {
                ProtocolTab(llm, transcript, title, availability is LlmAvailability.Available, onShareText, onCopy)
            } else {
                QaTab(llm, transcript, availability is LlmAvailability.Available)
            }
        }
    }
}

@Composable
private fun ProtocolTab(
    llm: LlmController,
    transcript: EditableTranscript,
    title: String,
    available: Boolean,
    onShareText: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val ui by llm.ui.collectAsState()
    val scroll = rememberScrollState()
    LaunchedEffect(ui.protocol.length) { if (ui.running) scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ui.protocol.isBlank() && !ui.running) {
            Text(
                "Языковая модель на устройстве составит протокол по расшифровке «$title»: резюме, обсуждённые вопросы, решения и поручения. " +
                    "Это занимает время (десятки секунд и больше) и выполняется без интернета.",
                style = MaterialTheme.typography.bodyLarge, color = Brutal.ink,
            )
        }
        if (ui.running) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui.status.ifBlank { "Генерация…" }, style = MaterialTheme.typography.titleSmall)
                BrutalProgress(null)
            }
        }
        ui.error?.let { MessageCard(it, isError = it != "Остановлено") }
        // lite: Qwen3-0.6B слаб для протокола -- честно помечаем результат (llm.quality = draft)
        if (ui.draft && (ui.protocol.isNotBlank() || ui.running)) {
            BrutalPlaque(DRAFT_NOTICE, kind = PlaqueKind.Accent, modifier = Modifier.testTag("draft_notice"))
        }
        if (ui.protocol.isNotBlank()) {
            BrutalCard(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                SelectionContainer {
                    Text(
                        ui.protocol,
                        modifier = Modifier.verticalScroll(scroll).padding(16.dp).fillMaxWidth().testTag("protocol_text"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        if (ui.protocol.isBlank()) Spacer(Modifier.weight(1f))
        if (ui.running) {
            BrutalButton(onClick = { llm.stop() }, style = BrutalButtonStyle.Secondary, modifier = Modifier.fillMaxWidth().testTag("btn_llm_stop")) { ButtonLabel("Остановить") }
        } else {
            BrutalButton(onClick = { llm.generateProtocol(transcript) }, enabled = available, modifier = Modifier.fillMaxWidth().testTag("btn_make_protocol")) {
                ButtonLabel(if (ui.protocolDone) "Составить заново" else "Составить протокол")
            }
            if (ui.protocol.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    BrutalButton(onClick = { onCopy(ui.protocol) }, style = BrutalButtonStyle.Secondary, modifier = Modifier.weight(1f)) {
                        Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp)); ButtonLabel("Копировать")
                    }
                    BrutalButton(onClick = { onShareText(ui.protocol) }, style = BrutalButtonStyle.Secondary, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)); ButtonLabel("Поделиться")
                    }
                }
            }
        }
    }
}

@Composable
private fun QaTab(llm: LlmController, transcript: EditableTranscript, available: Boolean) {
    val ui by llm.ui.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.qa.size, ui.qa.lastOrNull()?.answer?.length) {
        if (ui.qa.isNotEmpty()) listState.animateScrollToItem(ui.qa.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ui.qa.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ui.draft) BrutalPlaque("Ответы даёт упрощённая модель — проверяйте их по расшифровке.", kind = PlaqueKind.Accent, modifier = Modifier.testTag("draft_notice_qa"))
                        Text(
                            "Задайте вопрос по расшифровке — модель ответит только по её тексту, на устройстве и без интернета.",
                            style = MaterialTheme.typography.bodyLarge, color = Brutal.ink,
                        )
                        SUGGESTIONS.forEach { s -> BrutalChip(selected = false, onClick = { question = s }, label = s) }
                    }
                }
            }
            items(ui.qa) { item ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrutalCard(Modifier.fillMaxWidth(), fill = Brutal.accentSoft) {
                        Text(item.question, style = MaterialTheme.typography.titleSmall, color = Brutal.ink)
                    }
                    BrutalCard(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(item.answer.ifBlank { if (item.done) "" else "…" }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            ui.error?.let { e -> item { MessageCard(e, isError = true) } }
        }
        if (ui.running) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.status.ifBlank { "Генерация…" }, style = MaterialTheme.typography.labelMedium)
                BrutalProgress(null, height = 12.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrutalTextField(
                value = question, onValueChange = { question = it }, modifier = Modifier.weight(1f).testTag("qa_field"),
                label = "Вопрос по расшифровке", maxLines = 3, enabled = available,
            )
            if (ui.running) {
                BrutalButton(onClick = { llm.stop() }, style = BrutalButtonStyle.Secondary, height = 52.dp) { ButtonLabel("Стоп") }
            } else {
                BrutalIconButton(
                    onClick = { llm.ask(question, transcript); question = "" }, contentDescription = "Спросить",
                    fill = Brutal.accent, enabled = available && question.isNotBlank(), modifier = Modifier.size(56.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }
            }
        }
    }
}
