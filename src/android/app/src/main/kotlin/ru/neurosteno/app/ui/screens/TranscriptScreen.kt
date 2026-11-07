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
import ru.neurosteno.app.ui.components.BrutalDialog
import ru.neurosteno.app.ui.components.BrutalSheet
import ru.neurosteno.app.ui.components.BrutalSnackbarHost
import ru.neurosteno.app.ui.components.DialogButton
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalChip
import ru.neurosteno.app.ui.theme.BrutalDims
import ru.neurosteno.app.ui.theme.BrutalDivider
import ru.neurosteno.app.ui.theme.BrutalIconButton
import ru.neurosteno.app.ui.theme.BrutalShape
import ru.neurosteno.app.ui.theme.BrutalSlider
import ru.neurosteno.app.ui.theme.BrutalSurface
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.SpeakerChip
import ru.neurosteno.app.ui.theme.SpeakerSquare
import ru.neurosteno.app.ui.theme.SectionTitle
import ru.neurosteno.app.ui.theme.BrutalTextField
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.defaultMinSize
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
    /** Переходы в «Выжимку» (B50) и «AI-поиск» (B51); `null` -- возможность недоступна в этой сборке (плитка «СКОРО»). */
    onSummary: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    /** Просьба открыть реплику из выжимки/поиска: прокрутка к реплике на времени и плеер на него. */
    focusMs: Long? = null,
    focusToken: Long = 0,
    onFocusConsumed: () -> Unit = {},
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
    // переход из выжимки/поиска: к реплике на нужном времени, подсветка на пару секунд, плеер встаёт на это место
    var flashIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(focusToken, utterances.size) {
        val ms = focusMs ?: return@LaunchedEffect
        if (utterances.isEmpty()) return@LaunchedEffect
        val i = TranscriptEditor.indexAt(utterances, ms).let { if (it >= 0) it else 0 }
        programmatic = true
        try { listState.animateScrollToItem(i + HEADER_ITEMS, scrollOffset = -80) } finally { programmatic = false }
        flashIndex = i
        follow = true
        if (player.available) session.player.playFrom(utterances[i].startMs)
        onFocusConsumed()
        kotlinx.coroutines.delay(2_500)
        if (flashIndex == i) flashIndex = -1
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
        containerColor = Brutal.bg,
        topBar = {
          if (selection != null) {
            NeuroTopBar(
                title = "Выбрано: ${selection.size}",
                onBack = { selRaw = -1L },
                actions = {
                    BrutalButton(onClick = { showFragmentSheet = true }, height = 48.dp, modifier = Modifier.testTag("btn_fragment_reprocess")) { ButtonLabel("Заново") }
                },
            )
          } else
            NeuroTopBar(
                title = entry?.title ?: "Расшифровка",
                onBack = onBack,
                actions = {
                    if (canUndo) {
                        BrutalIconButton(onClick = { session.undo() }, contentDescription = "Отменить последнюю правку") { Icon(Icons.Default.Refresh, contentDescription = null) }
                    }
                    BrutalIconButton(onClick = { searching = !searching; if (!searching) session.setQuery("") }, contentDescription = "Поиск по тексту", fill = if (searching) Brutal.accent else Brutal.bg) {
                        Icon(Icons.Default.Search, contentDescription = null)
                    }
                    BrutalIconButton(onClick = { showSpeakers = true }, contentDescription = "Говорящие") { Icon(Icons.Default.Person, contentDescription = null) }
                },
            )
        },
        bottomBar = { if (player.available) PlayerBar(player, session.player, utterances, highlight) },
        snackbarHost = { BrutalSnackbarHost(snackbar) },
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "header") {
                        Header(
                            entry = entry, transcript = transcript, llmAvailability = llmAvailability,
                            onExportJson = { onExport(ExportFormat.JSON) }, onMore = { showFormats = true }, onProtocol = onProtocol,
                            onSummary = onSummary, onSearch = onSearch,
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
                            highlighted = (u.startMs to u.endMs) in highlight || i == flashIndex,
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
                BrutalButton(onClick = { follow = true }, height = 44.dp, modifier = Modifier.padding(12.dp)) { ButtonLabel("К текущей реплике") }
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
        BrutalSheet(onDismissRequest = { showFormats = false }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Экспорт и отправка")
                listOf(
                    ExportFormat.TXT to "Текст с именами говорящих и временем",
                    ExportFormat.SRT to "Субтитры SubRip",
                    ExportFormat.VTT to "Субтитры WebVTT",
                    ExportFormat.PDF to "Печатный документ: шапка, говорящие, реплики и протокол (если составлен)",
                    ExportFormat.FULL_JSON to "Слова, уверенность, модели, время по стадиям, имена говорящих",
                ).forEach { (f, hint) ->
                    FormatRow(f.label, hint) { showFormats = false; onExport(f) }
                }
                FormatRow("Поделиться", "Отправить текст расшифровки в другое приложение") { showFormats = false; onShare() }
                Text(
                    "«Экспорт JSON» — строго формат ТЗ, в нём всегда speaker_N; имена говорящих попадают в TXT, SRT, VTT и расширенный JSON.",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private const val HEADER_ITEMS = 1

@Composable
private fun FormatRow(title: String, hint: String, onClick: () -> Unit) {
    BrutalCard(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$title. $hint" },
        onClick = onClick, shadow = BrutalDims.ShadowSmall + 1.dp,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
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
    onSummary: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BrutalTag(Format.clock(entry?.durationMs ?: transcript.durationMs), fill = Brutal.accent)
            BrutalTag(Format.plural(transcript.speakerIds.size, "говорящий", "говорящих", "говорящих"))
            BrutalTag(Format.plural(transcript.utterances.size, "реплика", "реплики", "реплик"))
            if (entry?.edited == true) BrutalTag("ИЗМЕНЕНО", fill = Brutal.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            BrutalButton(onClick = onExportJson, modifier = Modifier.weight(1f).testTag(Tags.EXPORT_JSON)) { ButtonLabel("Экспорт JSON") }
            BrutalButton(onClick = onMore, style = BrutalButtonStyle.Secondary, modifier = Modifier.weight(1f)) { ButtonLabel("Другие форматы") }
        }
        // Инструменты поверх расшифровки: «Протокол и вопросы» уже есть; «Выжимка» (B50) и «AI-поиск» (B51) -- места заложены, экраны появятся позже.
        BrutalButton(onClick = onProtocol, style = BrutalButtonStyle.Ink, modifier = Modifier.fillMaxWidth().testTag("btn_protocol")) {
            Icon(AppIcons.Description, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                ButtonLabel("Протокол заседания и вопросы")
                if (llmAvailability is LlmAvailability.Unavailable) {
                    Text("Недоступно на этом устройстве — нажмите, чтобы узнать причину", style = MaterialTheme.typography.labelSmall, color = Brutal.onInk)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (onSummary != null) ToolButton("Выжимка", onSummary, Modifier.weight(1f).testTag("btn_summary")) else ToolSlot("Выжимка", Modifier.weight(1f).testTag("slot_summary"))
            if (onSearch != null) ToolButton("AI-поиск", onSearch, Modifier.weight(1f).testTag("btn_ai_search")) else ToolSlot("AI-поиск", Modifier.weight(1f).testTag("slot_ai_search"))
        }
        entry?.warnings?.takeIf { it.isNotEmpty() }?.let { w ->
            Text("Предупреждения обработки: ${w.joinToString("; ")}", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
        }
    }
}

/** Рабочая кнопка инструмента поверх расшифровки («Выжимка», «AI-поиск»). */
@Composable
private fun ToolButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BrutalButton(onClick = onClick, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = modifier) { ButtonLabel(label) }
}

/** Заложенное место под будущий инструмент (B50/B51): неактивная кнопка с меткой «СКОРО». */
@Composable
private fun ToolSlot(label: String, modifier: Modifier = Modifier) {
    BrutalButton(onClick = {}, enabled = false, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = modifier, contentDescription = "$label: скоро") {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ButtonLabel(label)
            BrutalTag("СКОРО", textStyle = MaterialTheme.typography.labelSmall)
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
    // Заливка отражает состояние: выбрана -- инверсия, только что пересчитана -- акцент, звучит сейчас -- акцент; обычная -- фон.
    val fill = when {
        selected -> Brutal.ink
        highlighted || current -> Brutal.accent
        else -> Brutal.bg
    }
    val fg = when {
        selected -> Brutal.onInk
        highlighted || current -> Brutal.onAccent
        else -> Brutal.ink
    }
    val time = Format.clock(u.startMs)
    BrutalSurface(
        modifier = Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$name, $time. ${u.text}" + (if (current) ". Сейчас звучит" else "") +
                    (if (selected) ". Выбрана" else "") + (if (highlighted) ". Обработана заново" else "")
            }
            .combinedClickable(
                onClickLabel = if (selectionMode) (if (selected) "Снять выбор" else "Выбрать реплику") else "Воспроизвести с этого места",
                onClick = onClick, onLongClickLabel = "Выбрать для повторной обработки", onLongClick = onLongClick,
            ),
        fill = fill, contentColor = fg,
        shadow = if (matched) 6.dp else BrutalDims.Shadow,
        borderWidth = if (matched || selected) BrutalDims.BorderThick + 1.dp else BrutalDims.Border,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakerChip(name, u.speakerId, Modifier.weight(1f, fill = false))
                    BrutalTag(time, fill = if (selected) Brutal.ink else Brutal.bg)
                    if (highlighted) BrutalTag("НОВАЯ", fill = Brutal.ink)
                }
                Text(highlight(u.text, query), style = MaterialTheme.typography.bodyLarge, color = fg)
            }
            Box {
                BrutalIconButton(
                    onClick = onMenu, contentDescription = "Действия с репликой: $name, $time",
                    fill = if (selected) Brutal.ink else Brutal.bg,
                ) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss, containerColor = Brutal.bg, shape = BrutalShape, border = BorderStroke(BrutalDims.Border, Brutal.ink)) {
                    DropdownMenuItem(text = { Text("Обработать заново", style = MaterialTheme.typography.labelLarge) }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = onReprocess,
                        modifier = Modifier.testTag("menu_reprocess"))
                    DropdownMenuItem(text = { Text("Править текст", style = MaterialTheme.typography.labelLarge) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = onEdit)
                    DropdownMenuItem(text = { Text("Сменить говорящего", style = MaterialTheme.typography.labelLarge) }, leadingIcon = { Icon(Icons.Default.Person, null) }, onClick = onSpeaker)
                    if (canMerge) DropdownMenuItem(text = { Text("Объединить со следующей", style = MaterialTheme.typography.labelLarge) }, leadingIcon = { Icon(AppIcons.Merge, null) }, onClick = onMerge)
                    DropdownMenuItem(text = { Text("Удалить реплику", style = MaterialTheme.typography.labelLarge) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = onDelete)
                }
            }
        }
    }
}

/** Найденное в поиске -- чёрная плашка с белым текстом (читается и на акцентной, и на белой заливке карточки). */
@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    val ranges = SearchUtil.ranges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val bg = Brutal.ink
    val fg = Brutal.onInk
    return buildAnnotatedString {
        var pos = 0
        for (r in ranges) {
            append(text.substring(pos, r.first))
            withStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.Bold)) { append(text.substring(r.first, r.last + 1)) }
            pos = r.last + 1
        }
        append(text.substring(pos))
    }
}

@Composable
private fun SearchBar(query: String, count: Int, cursor: Int, onQuery: (String) -> Unit, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BrutalTextField(
                value = query, onValueChange = onQuery,
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("search_field"),
                label = "Поиск по тексту", singleLine = true,
            )
            BrutalIconButton(onClick = { onStep(-1) }, contentDescription = "Предыдущее совпадение", enabled = count > 0) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) }
            BrutalIconButton(onClick = { onStep(1) }, contentDescription = "Следующее совпадение", enabled = count > 0) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
            BrutalIconButton(onClick = onClose, contentDescription = "Закрыть поиск") { Icon(Icons.Default.Close, contentDescription = null) }
        }
        BrutalTag(if (query.isBlank()) "Введите слово или фразу" else if (count == 0) "Ничего не найдено" else "${cursor + 1} из $count", textStyle = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PlayerBar(state: PlayerState, player: AudioPlayer, utterances: List<Utterance>, highlight: Set<Pair<Long, Long>>) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().background(Brutal.bg)) {
        BrutalDivider(thickness = BrutalDims.BorderThick)
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val btn = if (landscape) 56.dp else 64.dp
        val seek: @Composable (Modifier) -> Unit = { mod ->
            Row(mod, verticalAlignment = Alignment.CenterVertically) {
                BrutalTag(Format.clock(((dragging ?: (state.positionMs.toFloat() / duration)) * duration).toLong()), fill = Brutal.ink)
                BrutalSlider(
                    value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { player.seekTo((it * duration).toLong()) }; dragging = null },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Положение в записи" },
                )
                BrutalTag(Format.clock(state.durationMs))
            }
        }
        val controls: @Composable (Modifier) -> Unit = { mod ->
            Row(mod, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                BrutalIconButton(onClick = { player.seekBy(-10_000) }, contentDescription = "Назад на 10 секунд") { Icon(AppIcons.Replay10, contentDescription = null) }
                BrutalIconButton(
                    onClick = { player.toggle() }, contentDescription = if (state.playing) "Пауза" else "Воспроизвести", fill = Brutal.accent,
                    modifier = Modifier.size(btn).testTag("btn_play_pause"),
                ) {
                    if (state.playing) Icon(AppIcons.Pause, contentDescription = null, modifier = Modifier.size(32.dp)) else Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(34.dp))
                }
                BrutalIconButton(onClick = { player.seekBy(10_000) }, contentDescription = "Вперёд на 10 секунд") { Icon(AppIcons.Forward10, contentDescription = null) }
                Box {
                    BrutalChip(
                        selected = false, onClick = { speedMenu = true }, label = speedLabel(state.speed),
                        modifier = Modifier.semantics { contentDescription = "Скорость воспроизведения ${speedLabel(state.speed)}" },
                    )
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }, containerColor = Brutal.bg, shape = BrutalShape, border = BorderStroke(BrutalDims.Border, Brutal.ink)) {
                        AudioPlayer.SPEEDS.forEach { s ->
                            DropdownMenuItem(text = { Text(speedLabel(s), style = MaterialTheme.typography.labelLarge) }, onClick = { player.setSpeed(s); speedMenu = false })
                        }
                    }
                }
            }
        }
        // дорожка говорящих под плеером (B54): те же цвета, что у меток реплик; тап/протяжка -- перемотка
        val timeline: @Composable () -> Unit = {
            ru.neurosteno.app.ui.components.SpeakerTimeline(
                utterances = utterances, highlighted = highlight, durationMs = state.durationMs, positionMs = state.positionMs,
                onSeek = { player.seekTo(it) }, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (landscape) {
            // в альбомной ориентации плеер -- одна строка, чтобы остаться компактным и не съедать список реплик
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    controls(Modifier)
                    seek(Modifier.weight(1f).padding(start = 12.dp))
                }
                timeline()
            }
        } else {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                seek(Modifier.fillMaxWidth())
                controls(Modifier.fillMaxWidth())
                timeline()
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
    BrutalDialog(
        onDismissRequest = onDismiss,
        title = "Правка реплики",
        text = {
            BrutalTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { testTagsAsResourceId = true }.testTag("edit_text_field"),
                minLines = 3, maxLines = 8, label = "Текст реплики",
            )
        },
        confirmButton = { DialogButton("Сохранить", { onSave(text) }, enabled = text.isNotBlank()) },
        dismissButton = { DialogButton("Отмена", onDismiss, primary = false) },
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
    BrutalDialog(
        onDismissRequest = onDismiss,
        title = "Кто говорит?",
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                transcript.speakerIds.forEach { id ->
                    SpeakerChoiceRow(id, transcript.displayName(id), selected = id == currentId) { onPick(id) }
                }
                SpeakerChoiceRow(newSpeakerId, "Новый говорящий", selected = false) { onPick(newSpeakerId) }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton("Отмена", onDismiss, primary = false) },
    )
}

@Composable
private fun SpeakerChoiceRow(id: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .background(if (selected) Brutal.accent else Brutal.bg, BrutalShape)
            .border(BrutalDims.Border, Brutal.ink, BrutalShape)
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (selected) "$label, выбран" else label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SpeakerSquare(id, 22.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Brutal.onAccent else Brutal.ink)
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
    BrutalDialog(
        onDismissRequest = onDismiss,
        title = "Говорящие",
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Имена показываются в расшифровке, TXT, SRT, VTT и расширенном JSON. В JSON по ТЗ остаётся speaker_N.",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                )
                transcript.speakerIds.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpeakerSquare(id, 22.dp)
                        BrutalTextField(
                            value = names.value[id].orEmpty(),
                            onValueChange = { v -> names.value = names.value + (id to v) },
                            singleLine = true, label = EditableTranscript.defaultName(id) + " (" + id + ")",
                            modifier = Modifier.weight(1f).testTag("speaker_name_$id"),
                        )
                    }
                }
            }
        },
        confirmButton = { DialogButton("Готово", { onApply(names.value) }) },
        dismissButton = { DialogButton("Отмена", onDismiss, primary = false) },
    )
}
