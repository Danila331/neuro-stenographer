package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
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

private val SUGGESTIONS = listOf("О чём разговор?", "Какие решения приняты?", "Кому какие задачи поставлены?")

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
    Scaffold(topBar = { NeuroTopBar("Протокол и вопросы", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Протокол заседания") }, modifier = Modifier.testTag("tab_protocol"))
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Вопрос по расшифровке") }, modifier = Modifier.testTag("tab_qa"))
            }
            if (availability is LlmAvailability.Unavailable) {
                MessageCard(availability.reason, isError = true, modifier = Modifier.padding(16.dp))
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.protocol.isBlank() && !ui.running) {
            Text(
                "Языковая модель на устройстве составит протокол по расшифровке «$title»: резюме, обсуждённые вопросы, решения и поручения. " +
                    "Это занимает время (десятки секунд и больше) и выполняется без интернета.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.running) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.status.ifBlank { "Генерация…" }, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        ui.error?.let { MessageCard(it, isError = it != "Остановлено") }
        if (ui.protocol.isNotBlank()) {
            Card(
                Modifier.weight(1f, fill = true).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                SelectionContainer {
                    Text(
                        ui.protocol,
                        modifier = Modifier.verticalScroll(scroll).padding(16.dp).fillMaxWidth().testTag("protocol_text"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else {
            Column(Modifier.weight(1f)) {}
        }
        if (ui.running) {
            OutlinedButton(onClick = { llm.stop() }, modifier = Modifier.fillMaxWidth().testTag("btn_llm_stop")) { Text("Остановить") }
        } else {
            Button(onClick = { llm.generateProtocol(transcript) }, enabled = available, modifier = Modifier.fillMaxWidth().testTag("btn_make_protocol")) {
                Text(if (ui.protocolDone) "Составить заново" else "Составить протокол")
            }
            if (ui.protocol.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onCopy(ui.protocol) }, modifier = Modifier.weight(1f)) {
                        Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Копировать")
                    }
                    OutlinedButton(onClick = { onShareText(ui.protocol) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Поделиться")
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.qa.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Задайте вопрос по расшифровке — модель ответит только по её тексту, на устройстве и без интернета.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SUGGESTIONS.forEach { s ->
                            AssistChip(onClick = { question = s }, label = { Text(s) })
                        }
                    }
                }
            }
            items(ui.qa) { item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Text(item.question, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(
                                item.answer.ifBlank { if (item.done) "" else "…" },
                                Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
            ui.error?.let { e -> item { MessageCard(e, isError = true) } }
        }
        if (ui.running) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.status.ifBlank { "Генерация…" }, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = question, onValueChange = { question = it }, modifier = Modifier.weight(1f).testTag("qa_field"),
                label = { Text("Вопрос по расшифровке") }, maxLines = 3, enabled = available,
            )
            if (ui.running) {
                OutlinedButton(onClick = { llm.stop() }) { Text("Стоп") }
            } else {
                IconButton(onClick = { llm.ask(question, transcript); question = "" }, enabled = available && question.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Спросить")
                }
            }
        }
    }
}
