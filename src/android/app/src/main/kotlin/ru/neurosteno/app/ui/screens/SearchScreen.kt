package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.insight.AnswerUi
import ru.neurosteno.app.insight.CitationParser
import ru.neurosteno.app.insight.Highlight
import ru.neurosteno.app.insight.IndexState
import ru.neurosteno.app.insight.SearchHit
import ru.neurosteno.app.insight.SearchPhase
import ru.neurosteno.app.insight.SearchScope
import ru.neurosteno.app.insight.SearchUi
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalChip
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalSurface
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.BrutalTextField
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.PlaqueKind
import ru.neurosteno.app.ui.theme.SectionTitle
import ru.neurosteno.app.ui.theme.SpeakerSquare

class SearchActions(
    val onBack: () -> Unit,
    val onQuery: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onScope: (SearchScope) -> Unit,
    val onAnswer: () -> Unit,
    val onStopAnswer: () -> Unit,
    /** Перейти к реплике записи [recordId] на времени [ms] (плеер встаёт на это место). */
    val onOpen: (recordId: String, ms: Long) -> Unit,
)

/** Подсказки-примеры вопросов; [speakerName] -- имя первого говорящего открытой записи (если есть). */
fun searchSuggestions(speakerName: String?): List<String> = buildList {
    add("Что говорил ${speakerName ?: "Говорящий 1"} про ")
    add("Что обсуждали по теме ")
    add("Какие решения приняли?")
}

/**
 * «AI-поиск» (B51): вопрос и подсказки, область «эта запись / вся история», сначала найденные фрагменты (говорящий, время, подсветка
 * совпадений), затем ответ ИИ со ссылками `[мм:сс, Говорящий N]` -- ссылки кликабельны. Состояния: пусто, отказ, ИИ недоступен,
 * индекс строится.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(ui: SearchUi, suggestions: List<String>, recordTitle: String?, actions: SearchActions) {
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("AI-поиск", onBack = actions.onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrutalTextField(
                        value = ui.query, onValueChange = actions.onQuery,
                        modifier = Modifier.fillMaxWidth().testTag("search_query"),
                        label = "Вопрос или слова для поиска", singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { actions.onSubmit() }),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ui.canScopeRecord) BrutalChip(ui.scope == SearchScope.RECORD, { actions.onScope(SearchScope.RECORD) }, "Эта запись", Modifier.testTag("scope_record"))
                        BrutalChip(ui.scope == SearchScope.HISTORY, { actions.onScope(SearchScope.HISTORY) }, "Вся история", Modifier.testTag("scope_history"))
                    }
                    if (ui.scope == SearchScope.RECORD && recordTitle != null) Text("Запись: $recordTitle", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
                    BrutalButton(onClick = actions.onSubmit, enabled = ui.query.isNotBlank() && ui.index !is IndexState.Unavailable, modifier = Modifier.fillMaxWidth().testTag("btn_search_go")) { ButtonLabel("Найти") }
                }
            }
            when (val idx = ui.index) {
                is IndexState.Building -> item {
                    BrutalPlaque(
                        "Индекс строится в фоне: ${idx.done} из ${idx.total} фрагментов. Поиск по уже проиндексированному работает, остальное добавится.",
                        title = "Поиск готовится…", modifier = Modifier.testTag("search_index_building"),
                    ) { BrutalProgress(idx.fraction) }
                }
                is IndexState.Unavailable -> item { BrutalPlaque(idx.reason, kind = PlaqueKind.Error, title = "Поиск недоступен", modifier = Modifier.testTag("search_index_unavailable")) }
                IndexState.Ready -> {}
            }
            when (ui.phase) {
                SearchPhase.Idle -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Примеры вопросов", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEachIndexed { i, s -> BrutalChip(false, { actions.onQuery(s) }, s.trim().let { if (it.endsWith("?")) it else "$it …" }, Modifier.testTag("suggestion_$i")) }
                        }
                        Text(
                            "Поиск работает на устройстве: по словам и по смыслу. Можно спросить, что говорил конкретный говорящий.",
                            style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                        )
                    }
                }
                SearchPhase.Searching -> item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Ищу…", style = MaterialTheme.typography.bodyLarge); BrutalProgress(null) } }
                SearchPhase.NoResults -> item {
                    BrutalPlaque(
                        "Попробуйте другие слова" + (if (ui.scope == SearchScope.RECORD) " или область «Вся история»." else "."),
                        title = "Ничего не найдено", modifier = Modifier.testTag("search_no_results"),
                    )
                }
                SearchPhase.Error -> item { MessageCard(ui.error ?: "Не удалось выполнить поиск", isError = true) }
                SearchPhase.Results -> {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Найденные фрагменты")
                            BrutalTag("${ui.hits.size}", modifier = Modifier.testTag("search_hits_count"))
                        }
                    }
                    itemsIndexed(ui.hits) { i, h -> HitCard(i, h, showRecord = ui.scope == SearchScope.HISTORY, onOpen = actions.onOpen) }
                    item { SectionTitle("Ответ ИИ") }
                    item { AnswerBlock(ui, actions) }
                }
            }
        }
    }
}

@Composable
private fun HitCard(index: Int, hit: SearchHit, showRecord: Boolean, onOpen: (String, Long) -> Unit) {
    BrutalCard(Modifier.fillMaxWidth().testTag("search_hit_$index"), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Открыть запись на ${Format.clock(hit.startMs)}") { onOpen(hit.recordId, hit.startMs) }.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            BrutalTag("${Format.clock(hit.startMs)}–${Format.clock(hit.endMs)}", fill = Brutal.accent)
            if (showRecord) Text(hit.recordTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Column(Modifier.padding(bottom = 8.dp)) {
            hit.lines.forEachIndexed { j, l ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Перейти к реплике ${Format.clock(l.startMs)}") { onOpen(hit.recordId, l.startMs) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "${l.speakerName}, ${Format.clock(l.startMs)}. ${l.text}" }
                        .testTag("search_hit_${index}_line_$j"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SpeakerSquare(l.speakerId, 20.dp)
                    Column(Modifier.weight(1f)) {
                        Text("${l.speakerName} · ${Format.clock(l.startMs)}", style = MaterialTheme.typography.labelMedium, color = Brutal.muted)
                        Text(highlighted(l.text, hit.terms), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** Текст реплики с подсветкой совпадений: акцентная заливка и чёрный текст (читается в обеих темах). */
@Composable
private fun highlighted(text: String, terms: List<String>): AnnotatedString {
    val ranges = Highlight.ranges(text, terms)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val accent = Brutal.accent
    return buildAnnotatedString {
        var pos = 0
        for (r in ranges) {
            append(text.substring(pos, r.first))
            withStyle(SpanStyle(background = accent, color = Brutal.onAccent, fontWeight = FontWeight.Bold)) { append(text.substring(r.first, r.last + 1)) }
            pos = r.last + 1
        }
        append(text.substring(pos))
    }
}

@Composable
private fun AnswerBlock(ui: SearchUi, actions: SearchActions) {
    when (val a = ui.answer) {
        AnswerUi.Hidden -> {}
        AnswerUi.Ready -> BrutalCard(Modifier.fillMaxWidth().testTag("answer_ready")) {
            Text("Короткий ответ по найденным фрагментам со ссылками на реплики. Генерация занимает несколько секунд.", style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, modifier = Modifier.padding(bottom = 10.dp))
            BrutalButton(onClick = actions.onAnswer, modifier = Modifier.fillMaxWidth().testTag("btn_answer")) { ButtonLabel("Ответить") }
        }
        is AnswerUi.Generating -> BrutalCard(Modifier.fillMaxWidth().testTag("answer_generating")) {
            if (a.partial.isNotBlank()) AnswerText(a.partial, ui, actions.onOpen) else Text("Читаю фрагменты…", style = MaterialTheme.typography.bodyLarge)
            BrutalProgress(null, Modifier.padding(top = 10.dp))
            BrutalButton(onClick = actions.onStopAnswer, style = BrutalButtonStyle.Secondary, height = 44.dp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("btn_answer_stop")) { ButtonLabel("Остановить") }
        }
        is AnswerUi.Done -> BrutalCard(Modifier.fillMaxWidth().testTag("answer_done"), fill = Brutal.panel) {
            AnswerText(a.text, ui, actions.onOpen)
            Text("Ответ ИИ может ошибаться: нажмите на ссылку и проверьте реплику.", style = MaterialTheme.typography.bodySmall, color = Brutal.muted, modifier = Modifier.padding(top = 10.dp))
            BrutalButton(onClick = actions.onAnswer, style = BrutalButtonStyle.Secondary, height = 44.dp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("btn_answer_again")) { ButtonLabel("Ответить заново") }
        }
        AnswerUi.Refused -> BrutalPlaque(
            "В найденных фрагментах ответа нет. Проверьте фрагменты выше или переформулируйте вопрос.",
            title = "В записи нет ответа", modifier = Modifier.testTag("answer_refused"),
        )
        is AnswerUi.Unavailable -> BrutalPlaque("ИИ недоступен: ${a.reason}. Показаны только найденные фрагменты.", title = "Ответа ИИ нет", modifier = Modifier.testTag("answer_unavailable"))
        AnswerUi.Disabled -> BrutalPlaque("В этом варианте приложения ответ ИИ выключен — доступен список найденных реплик.", title = "Ответа ИИ нет", modifier = Modifier.testTag("answer_disabled"))
        is AnswerUi.Failed -> MessageCard(a.message, isError = true, modifier = Modifier.testTag("answer_failed")) {
            BrutalButton(onClick = actions.onAnswer, style = BrutalButtonStyle.Secondary, height = 44.dp, modifier = Modifier.fillMaxWidth()) { ButtonLabel("Повторить") }
        }
    }
}

/** Ответ ИИ: ссылки `[мм:сс, Говорящий N]` -- кликабельные, ведут к реплике нужной записи. */
@Composable
private fun AnswerText(text: String, ui: SearchUi, onOpen: (String, Long) -> Unit) {
    val link = SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, background = Brutal.accent, color = Brutal.onAccent)
    val annotated = buildAnnotatedString {
        for (part in CitationParser.parse(text)) when (part) {
            is CitationParser.Part.Text -> append(part.text)
            is CitationParser.Part.Cite -> {
                val rid = CitationParser.resolveRecord(part.citation, ui.hits, ui.recordId)
                if (rid != null) withLink(LinkAnnotation.Clickable("cite", TextLinkStyles(link)) { onOpen(rid, part.citation.startMs) }) { append(part.raw) }
                else append(part.raw)
            }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("answer_text"))
}
