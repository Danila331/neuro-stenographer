package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.neurosteno.app.FragmentParams
import ru.neurosteno.app.FragmentState
import ru.neurosteno.app.FragmentUi
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.edit.FragmentSelection
import ru.neurosteno.app.edit.SearchUtil
import ru.neurosteno.app.edit.TranscriptEditor
import ru.neurosteno.app.export.ExportFormat
import ru.neurosteno.app.llm.LlmAvailability
import ru.neurosteno.app.player.AudioPlayer
import ru.neurosteno.app.player.PlayerState
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.TranscriptSession
import ru.neurosteno.app.ui.components.AppIcons
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.components.SpeakerDot
import ru.neurosteno.app.ui.speakerColor
import ru.neurosteno.core.Utterance

/** Расшифровка: реплики с цветными метками, плеер, поиск, правки, экспорт и протокол. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TranscriptScreen(
    session: TranscriptSession,
    llmAvailability: LlmAvailability,
    onBack: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onShare: () -> Unit,
    onProtocol: () -> Unit,
    onStartFragment: (FragmentParams) -> Unit = {},
    onCancelFragment: () -> Unit = {},
) {
    val doc by session.doc.collectAsState()
    val highlight by session.highlight.collectAsState()
    val canUndoFragment by session.canUndoFragment.collectAsState()
    val fragmentState by FragmentState.state.collectAsState()
    val entry by session.entry.collectAsState()
    val player by session.player.state.collectAsState()
    val query by session.query.collectAsState()
    val matches by session.matches.collectAsState()
    val cursor by session.matchCursor.collectAsState()
    val canUndo by session.canUndo.collectAsState()
    val loadError by session.loadError.collectAsState()

    var searching by rememberSaveable { mutableStateOf(false) }
    var showSpeakers by rememberSaveable { mutableStateOf(false) }
    var showFormats by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var editIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var speakerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var menuIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    // выделение соседних реплик для «Обработать заново» (кодируется одним числом, -1 -- нет выделения)
    var selRaw by rememberSaveable { mutableLongStateOf(-1L) }
    var showFragmentSheet by rememberSaveable { mutableStateOf(false) }
    val fragmentRunning = fragmentState as? FragmentUi.Running
    val myFragmentRunning = fragmentRunning?.takeIf { it.entryId == session.entryId }

    // «Назад» при открытом поиске сначала закрывает поиск (а не выходит с экрана)
    androidx.activity.compose.BackHandler(enabled = searching) { searching = false; session.setQuery("") }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val transcript = doc?.transcript
    val utterances = transcript?.utterances.orEmpty()
    val selection = FragmentSelection.clamp(if (selRaw < 0) null else FragmentSelection.decode(selRaw), utterances.size)
    androidx.activity.compose.BackHandler(enabled = selection != null) { selRaw = -1L; showFragmentSheet = false }
    val currentIndex by remember(utterances) {
        derivedStateOf { if (player.playing || player.positionMs > 0) TranscriptEditor.indexAt(utterances, player.positionMs) else -1 }
    }

    // автопрокрутка к текущей реплике во время воспроизведения; ручная прокрутка пользователем отключает слежение
    var programmatic by remember { mutableStateOf(false) }
    LaunchedEffect(currentIndex, follow, player.playing) {
        if (follow && player.playing && currentIndex >= 0) {
            programmatic = true
            try { listState.animateScrollToItem(currentIndex + HEADER_ITEMS, scrollOffset = -80) } finally { programmatic = false }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (scrolling && !programmatic && player.playing) follow = false }
    }

    // Результат пересчёта фрагмента (сервис уже сохранил его на диск): перечитываем, подсвечиваем, предлагаем «Отменить».
    // Подписка на поток, а не LaunchedEffect(state): consume() меняет состояние и отменил бы собственный эффект на полпути.
    LaunchedEffect(session.entryId) {
        FragmentState.state.collect { fs ->
            when {
                fs is FragmentUi.Finished && fs.entryId == session.entryId -> {
                    FragmentState.consume()
                    selRaw = -1L
                    if (fs.changed) {
                        session.reloadAfterFragment(fs.added)
                        val first = fs.added.firstOrNull()
                        session.doc.value?.transcript?.utterances?.indexOfFirst { first != null && it.startMs == first.startMs }?.takeIf { it >= 0 }
                            ?.let { listState.animateScrollToItem(it + HEADER_ITEMS) }
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val msg = "${fs.label} обработан заново: " + Format.plural(fs.added.size, "новая реплика", "новые реплики", "новых реплик")
                            val r = snackbar.showSnackbar(msg, actionLabel = "Отменить", duration = SnackbarDuration.Long)
                            if (r == SnackbarResult.ActionPerformed) {
                                android.util.Log.i("Neurosteno", "fragment_undo entry=${session.entryId} range=${fs.label}")
                                if (session.undoFragment()) snackbar.showSnackbar("Возвращено как было")
                            }
                        }
                    } else {
                        session.clearHighlight()
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(fs.message, duration = SnackbarDuration.Long)
                        }
                    }
                }
                fs is FragmentUi.Failed && fs.entryId == session.entryId -> {
                    FragmentState.consume()
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(fs.message, duration = SnackbarDuration.Long)
                    }
                }
            }
        }
    }

    fun undoable(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, actionLabel = "Отменить", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) session.undo()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
          if (selection != null) {
            NeuroTopBar(
                title = "Выбрано: ${selection.size}",
                onBack = { selRaw = -1L },
                actions = {
                    androidx.compose.material3.TextButton(onClick = { showFragmentSheet = true }, modifier = Modifier.testTag("btn_fragment_reprocess")) {
                        Text("Обработать заново")
                    }
                },
            )
          } else
            NeuroTopBar(
                title = entry?.title ?: "Расшифровка",
                onBack = onBack,
                actions = {
                    if (canUndo) {
                        IconButton(onClick = { session.undo() }) { Icon(Icons.Default.Refresh, contentDescription = "Отменить последнюю правку") }
                    }
                    IconButton(onClick = { searching = !searching; if (!searching) session.setQuery("") }) {
                        Icon(Icons.Default.Search, contentDescription = "Поиск по тексту")
                    }
                    IconButton(onClick = { showSpeakers = true }) { Icon(Icons.Default.Person, contentDescription = "Говорящие") }
                },
            )
        },
        bottomBar = { if (player.available) PlayerBar(player, session.player) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                SearchBar(
                    query = query, count = matches.size, cursor = cursor,
                    onQuery = { session.setQuery(it) },
                    onStep = { d -> session.stepMatch(d)?.let { idx -> scope.launch { listState.animateScrollToItem(idx + HEADER_ITEMS) } } },
                    onClose = { searching = false; session.setQuery("") },
                )
            }
            when {
                loadError != null -> MessageCard(loadError!!, isError = true, modifier = Modifier.padding(16.dp))
                transcript == null -> Text("Загрузка…", Modifier.padding(16.dp))
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "header") {
                        Header(
                            entry = entry, transcript = transcript, llmAvailability = llmAvailability,
                            onExportJson = { onExport(ExportFormat.JSON) }, onMore = { showFormats = true }, onProtocol = onProtocol,
                        )
                    }
                    if (utterances.isEmpty()) {
                        item(key = "empty") {
                            MessageCard("В записи не найдено речи. Попробуйте другой файл или проверьте уровень громкости.")
                        }
                    }
                    itemsIndexed(utterances, key = { i, u -> "$i-${u.startMs}-${u.speakerId}" }) { i, u ->
                        UtteranceCard(
                            u = u, name = transcript.displayName(u.speakerId), current = i == currentIndex,
                            matched = query.isNotBlank() && i in matches, query = query,
                            selected = selection != null && i in selection, selectionMode = selection != null,
                            highlighted = (u.startMs to u.endMs) in highlight,
                            canMerge = i < utterances.lastIndex, menuOpen = menuIndex == i,
                            onClick = {
                                if (selection != null) selRaw = selection.toggle(i)?.encode() ?: -1L
                                else if (player.available) { follow = true; session.player.playFrom(u.startMs) }
                                else scope.launch { snackbar.showSnackbar("Запись недоступна для воспроизведения") }
                            },
                            onLongClick = { menuIndex = null; selRaw = (selection?.toggle(i) ?: FragmentSelection(i, i)).encode() },
                            onReprocess = { menuIndex = null; selRaw = FragmentSelection(i, i).encode(); showFragmentSheet = true },
                            onMenu = { menuIndex = i }, onMenuDismiss = { menuIndex = null },
                            onEdit = { menuIndex = null; editIndex = i },
                            onSpeaker = { menuIndex = null; speakerIndex = i },
                            onMerge = { menuIndex = null; session.mergeWithNext(i); undoable("Реплики объединены") },
                            onDelete = { menuIndex = null; session.delete(i); undoable("Реплика удалена") },
                        )
                    }
                    item(key = "footer") { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
        if (player.playing && !follow) {
            // кнопка возвращает слежение за воспроизведением
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.BottomCenter) {
                AssistChip(onClick = { follow = true }, label = { Text("К текущей реплике") }, modifier = Modifier.padding(12.dp))
            }
        }
    }

        // идёт пересчёт фрагмента: индикатор с «Отменить» поверх расшифровки (экран можно покинуть -- сервис доработает)
        if (myFragmentRunning != null) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                    .clickable(enabled = true, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
                contentAlignment = Alignment.Center,
            ) {
                FragmentProgressCard(myFragmentRunning.label, Format.stageTitle(myFragmentRunning.stage), myFragmentRunning.overall, myFragmentRunning.cancelling, onCancelFragment)
            }
        }
    }

    if (showFragmentSheet && selection != null && utterances.isNotEmpty()) {
        val from = utterances[selection.first]
        val to = utterances[selection.last]
        FragmentOptionsSheet(
            rangeLabel = "${Format.clock(from.startMs)}–${Format.clock(to.endMs)}",
            utteranceCount = selection.size,
            recordingLanguage = entry?.language ?: "ru",
            blockedReason = when {
                fragmentRunning != null -> "Уже идёт пересчёт фрагмента."
                entry?.audioFile == null -> "Копия аудио недоступна: пересчитать фрагмент нельзя. Обработайте файл заново."
                else -> null
            },
            onStart = { opts ->
                showFragmentSheet = false
                val params = opts.toParams(session.entryId, from.startMs, to.endMs, entry?.language ?: "ru", selection.size)
                scope.launch {
                    session.flush() // сервис читает расшифровку с диска: несохранённые правки должны быть там
                    onStartFragment(params)
                }
            },
            onDismiss = { showFragmentSheet = false },
        )
    }

    // ---- диалоги ----
    editIndex?.let { i ->
        val u = utterances.getOrNull(i)
        if (u != null) EditTextDialog(u.text, onSave = { session.setText(i, it); editIndex = null }, onDismiss = { editIndex = null })
        else editIndex = null
    }
    speakerIndex?.let { i ->
        val u = utterances.getOrNull(i)
        if (u != null && transcript != null) {
            ChangeSpeakerDialog(
                transcript = transcript, currentId = u.speakerId,
                newSpeakerId = session.newSpeakerId(),
                onPick = { session.setSpeaker(i, it); speakerIndex = null },
                onDismiss = { speakerIndex = null },
            )
        } else speakerIndex = null
    }
    if (showSpeakers && transcript != null) {
        SpeakersDialog(
            transcript = transcript,
            onApply = { renames -> renames.forEach { (id, name) -> session.rename(id, name) }; showSpeakers = false },
            onDismiss = { showSpeakers = false },
        )
    }
    if (showFormats) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showFormats = false }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Экспорт и отправка", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                listOf(
                    ExportFormat.TXT to "Текст с именами говорящих и временем",
                    ExportFormat.SRT to "Субтитры SubRip",
                    ExportFormat.VTT to "Субтитры WebVTT",
                    ExportFormat.FULL_JSON to "Слова, уверенность, модели, время по стадиям, имена говорящих",
                ).forEach { (f, hint) ->
                    FormatRow(f.label, hint) { showFormats = false; onExport(f) }
                }
                FormatRow("Поделиться", "Отправить текст расшифровки в другое приложение") { showFormats = false; onShare() }
                Text(
                    "«Экспорт JSON» — строго формат ТЗ, в нём всегда speaker_N; имена говорящих попадают в TXT, SRT, VTT и расширенный JSON.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private const val HEADER_ITEMS = 1

@Composable
private fun FormatRow(title: String, hint: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $hint" },
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Header(
    entry: ru.neurosteno.app.data.HistoryEntry?,
    transcript: EditableTranscript,
    llmAvailability: LlmAvailability,
    onExportJson: () -> Unit,
    onMore: () -> Unit,
    onProtocol: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        val summary = buildString {
            append(Format.clock(entry?.durationMs ?: transcript.durationMs))
            append(" · ").append(Format.plural(transcript.speakerIds.size, "говорящий", "говорящих", "говорящих"))
            append(" · ").append(Format.plural(transcript.utterances.size, "реплика", "реплики", "реплик"))
            if (entry?.edited == true) append(" · изменено")
        }
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onExportJson, modifier = Modifier.weight(1f).testTag(Tags.EXPORT_JSON)) { Text("Экспорт JSON") }
            OutlinedButton(onClick = onMore, modifier = Modifier.weight(1f)) { Text("Другие форматы") }
        }
        FilledTonalButton(onClick = onProtocol, modifier = Modifier.fillMaxWidth().testTag("btn_protocol")) {
            Icon(AppIcons.Description, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text("Протокол заседания и вопросы")
                if (llmAvailability is LlmAvailability.Unavailable) {
                    Text("Недоступно на этом устройстве — нажмите, чтобы узнать причину", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        entry?.warnings?.takeIf { it.isNotEmpty() }?.let { w ->
            Text("Предупреждения обработки: ${w.joinToString("; ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UtteranceCard(
    u: Utterance,
    name: String,
    current: Boolean,
    matched: Boolean,
    query: String,
    selected: Boolean,
    selectionMode: Boolean,
    highlighted: Boolean,
    canMerge: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReprocess: () -> Unit,
    onMenu: () -> Unit,
    onMenuDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSpeaker: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = speakerColor(u.speakerId)
    val container = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        highlighted -> MaterialTheme.colorScheme.tertiaryContainer
        current -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val time = Format.clock(u.startMs)
    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (matched) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.medium) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $time. ${u.text}" + (if (current) ". Сейчас звучит" else "") +
                    (if (selected) ". Выбрана" else "") + (if (highlighted) ". Обработана заново" else "")
            }
            .combinedClickable(
                onClickLabel = if (selectionMode) (if (selected) "Снять выбор" else "Выбрать реплику") else "Воспроизвести с этого места",
                onClick = onClick, onLongClickLabel = "Выбрать для повторной обработки", onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 0.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakerDot(color)
                    Text(name, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(time, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(highlight(u.text, query), style = MaterialTheme.typography.bodyLarge)
            }
            Box {
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = "Действия с репликой: $name, $time") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                    DropdownMenuItem(text = { Text("Обработать заново") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = onReprocess,
                        modifier = Modifier.testTag("menu_reprocess"))
                    DropdownMenuItem(text = { Text("Править текст") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = onEdit)
                    DropdownMenuItem(text = { Text("Сменить говорящего") }, leadingIcon = { Icon(Icons.Default.Person, null) }, onClick = onSpeaker)
                    if (canMerge) DropdownMenuItem(text = { Text("Объединить со следующей") }, leadingIcon = { Icon(AppIcons.Merge, null) }, onClick = onMerge)
                    DropdownMenuItem(text = { Text("Удалить реплику") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    val ranges = SearchUtil.ranges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    return buildAnnotatedString {
        var pos = 0
        for (r in ranges) {
            append(text.substring(pos, r.first))
            withStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.SemiBold)) { append(text.substring(r.first, r.last + 1)) }
            pos = r.last + 1
        }
        append(text.substring(pos))
    }
}

@Composable
private fun SearchBar(query: String, count: Int, cursor: Int, onQuery: (String) -> Unit, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("search_field"),
            label = { Text("Поиск по тексту") },
            supportingText = { Text(if (query.isBlank()) "Введите слово или фразу" else if (count == 0) "Ничего не найдено" else "${cursor + 1} из $count") },
        )
        IconButton(onClick = { onStep(-1) }, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Предыдущее совпадение") }
        IconButton(onClick = { onStep(1) }, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Следующее совпадение") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Закрыть поиск") }
    }
}

@Composable
private fun PlayerBar(state: PlayerState, player: AudioPlayer) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(1)
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.clock(((dragging ?: (state.positionMs.toFloat() / duration)) * duration).toLong()), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                Slider(
                    value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { player.seekTo((it * duration).toLong()) }; dragging = null },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Положение в записи" },
                )
                Text(Format.clock(state.durationMs), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.seekBy(-10_000) }) { Icon(AppIcons.Replay10, contentDescription = "Назад на 10 секунд") }
                FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.size(52.dp).testTag("btn_play_pause")) {
                    if (state.playing) Icon(AppIcons.Pause, contentDescription = "Пауза") else Icon(Icons.Default.PlayArrow, contentDescription = "Воспроизвести")
                }
                IconButton(onClick = { player.seekBy(10_000) }) { Icon(AppIcons.Forward10, contentDescription = "Вперёд на 10 секунд") }
                Spacer(Modifier.width(12.dp))
                Box {
                    AssistChip(
                        onClick = { speedMenu = true },
                        label = { Text(speedLabel(state.speed)) },
                        modifier = Modifier.semantics { contentDescription = "Скорость воспроизведения ${speedLabel(state.speed)}" },
                    )
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        AudioPlayer.SPEEDS.forEach { s ->
                            DropdownMenuItem(text = { Text(speedLabel(s)) }, onClick = { player.setSpeed(s); speedMenu = false })
                        }
                    }
                }
            }
        }
    }
}

private fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().replace('.', ',')) + "×"

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EditTextDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Правка реплики") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { testTagsAsResourceId = true }.testTag("edit_text_field"),
                minLines = 3, maxLines = 8, label = { Text("Текст реплики") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ChangeSpeakerDialog(
    transcript: EditableTranscript,
    currentId: String,
    newSpeakerId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Кто говорит?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                transcript.speakerIds.forEach { id ->
                    SpeakerChoiceRow(id, transcript.displayName(id), selected = id == currentId) { onPick(id) }
                }
                SpeakerChoiceRow(newSpeakerId, "Новый говорящий", selected = false, labelOverride = true) { onPick(newSpeakerId) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SpeakerChoiceRow(id: String, label: String, selected: Boolean, labelOverride: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (selected) "$label, выбран" else label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        SpeakerDot(speakerColor(id))
        Spacer(Modifier.width(8.dp))
        Text(label, style = if (labelOverride) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SpeakersDialog(
    transcript: EditableTranscript,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val names = remember { mutableStateOf(transcript.speakerIds.associateWith { transcript.speakerNames[it].orEmpty() }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Говорящие") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Имена показываются в расшифровке, TXT, SRT, VTT и расширенном JSON. В JSON по ТЗ остаётся speaker_N.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                transcript.speakerIds.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpeakerDot(speakerColor(id), 14)
                        OutlinedTextField(
                            value = names.value[id].orEmpty(),
                            onValueChange = { v -> names.value = names.value + (id to v) },
                            singleLine = true, label = { Text(EditableTranscript.defaultName(id) + " (" + id + ")") },
                            modifier = Modifier.weight(1f).testTag("speaker_name_$id"),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(names.value) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
