package ru.neurosteno.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.neurosteno.app.BuildConfig
import ru.neurosteno.app.ProcessingState
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.export.ExportFormat
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.nav.Route
import ru.neurosteno.app.nav.rememberNavState
import ru.neurosteno.app.ui.screens.FailureScreen
import ru.neurosteno.app.ui.screens.HomeScreen
import ru.neurosteno.app.ui.screens.ModelActions
import ru.neurosteno.app.ui.screens.ModelsScreen
import ru.neurosteno.app.ui.screens.OnlineSetupScreen
import ru.neurosteno.app.ui.screens.ProcessingScreen
import ru.neurosteno.app.ui.screens.ProtocolScreen
import ru.neurosteno.app.ui.screens.LicenseTextScreen
import ru.neurosteno.app.ui.screens.LicensesScreen
import ru.neurosteno.app.ui.screens.RecordScreen
import ru.neurosteno.app.ui.screens.SearchActions
import ru.neurosteno.app.ui.screens.SearchScreen
import ru.neurosteno.app.ui.screens.SummaryActions
import ru.neurosteno.app.ui.screens.SummaryScreen
import ru.neurosteno.app.ui.screens.searchSuggestions
import ru.neurosteno.app.ui.screens.SettingsScreen
import ru.neurosteno.app.ui.screens.StatsScreen
import ru.neurosteno.app.ui.screens.TranscriptScreen
import ru.neurosteno.app.ui.theme.NeuroTheme

/** Всё, что экрану нужно от Activity: системные диалоги, разрешения, запуск обработки. */
interface AppHost {
    val appName: String
    fun pickAudio()
    fun startProcessing(uri: Uri, name: String)
    fun export(entryId: String, format: ExportFormat)
    fun shareText(text: String, subject: String)
    fun copyText(text: String)
    fun hasNotificationPermission(): Boolean
    fun requestNotificationPermission()
    fun hasRecordPermission(): Boolean
    fun requestRecordPermission(onResult: (Boolean) -> Unit)
    fun openAppSettings()
    fun moveToBackground()
    fun cancelProcessing()
    fun startFragment(params: ru.neurosteno.app.FragmentParams)
    fun cancelFragment()
    /** Цвет значков строки состояния/навигации под выбранную тему (светлые значки на тёмном фоне и наоборот). */
    fun applySystemBars(dark: Boolean)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(host: AppHost, vm: AppViewModel) {
    val graph = vm.graph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val dark = when (settings.theme) {
        ru.neurosteno.app.data.ThemeChoice.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ru.neurosteno.app.data.ThemeChoice.LIGHT -> false
        ru.neurosteno.app.data.ThemeChoice.DARK -> true
    }
    androidx.compose.runtime.SideEffect { host.applySystemBars(dark) }
    NeuroTheme(settings.theme) {
        // resource-id у тегов: на них опираются uiautomator-скрипты и автоматизация проверки
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            AppContent(host, vm)
        }
    }
}

@Composable
private fun AppContent(host: AppHost, vm: AppViewModel) {
    val graph = vm.graph
    val nav = rememberNavState()
    val processing by ProcessingState.processing.collectAsStateWithLifecycle()
    val models by ProcessingState.models.collectAsStateWithLifecycle()
    val entries by graph.history.entries.collectAsStateWithLifecycle()
    val rows by graph.modelsController.rows.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val modelMessage by graph.modelsController.lastMessage.collectAsStateWithLifecycle()
    val activeSet = rows.firstOrNull { it.active }

    // расшифровка готова -> сразу открываем её (без лишнего шага)
    LaunchedEffect(processing) {
        val p = processing
        if (p is ProcessingUi.Done) {
            nav.push(Route.Transcript(p.entryId))
            ProcessingState.reset()
        }
    }
    // сессии (плеер, правки) и LLM живут, пока их экран в стеке навигации
    LaunchedEffect(Unit) {
        snapshotFlow { nav.stack.toList() }.collect { stack ->
            vm.retain(
                transcripts = stack.filterIsInstance<Route.Transcript>().map { it.entryId }.toSet() + stack.filterIsInstance<Route.Protocol>().map { it.entryId } +
                    stack.filterIsInstance<Route.Summary>().map { it.entryId },
                protocolOpen = stack.any { it is Route.Protocol },
            )
        }
    }

    val focus by vm.focus.collectAsStateWithLifecycle()
    // переход к реплике записи [entryId] на времени [ms] (из выжимки или поиска): возвращаемся к открытой расшифровке либо открываем её
    fun openAt(entryId: String, ms: Long) {
        vm.requestFocus(entryId, ms)
        val target = Route.Transcript(entryId)
        val idx = nav.stack.indexOfLast { it == target }
        if (idx >= 0) while (nav.stack.size - 1 > idx) nav.pop() else nav.push(target)
    }

    BackHandler(enabled = nav.canPop) { nav.pop() }
    BackHandler(enabled = processing is ProcessingUi.Failed) { ProcessingState.reset() }
    BackHandler(enabled = processing is ProcessingUi.Running) { host.moveToBackground() }

    val modelActions = remember(graph) {
        val c = graph.modelsController
        ModelActions(c::startDownload, c::cancelDownload, c::discardPartial, c::activate, c::delete, c::restoreBuiltin)
    }
    val freeBytes = remember(rows, processing) { graph.filesDir().usableSpace }

    when (val p = processing) {
        is ProcessingUi.Running -> {
            var hintDismissed by rememberSaveable { mutableStateOf(false) }
            val prior = remember(entries) { entries.map { it.rtf }.filter { it > 0 }.take(5).let { if (it.isEmpty()) 0.0 else it.average() } }
            ProcessingScreen(
                state = p, models = models, priorRtf = prior,
                showNotificationHint = !hintDismissed && !host.hasNotificationPermission(),
                onEnableNotifications = { hintDismissed = true; host.requestNotificationPermission() },
                onDismissNotificationHint = { hintDismissed = true },
                onCancel = { host.cancelProcessing() },
            )
            return
        }
        is ProcessingUi.Failed -> {
            FailureScreen(
                state = p,
                onClose = { ProcessingState.reset() },
                onPickAnother = { ProcessingState.reset(); host.pickAudio() },
                onModels = { ProcessingState.reset(); nav.push(Route.Models) },
            )
            return
        }
        else -> Unit
    }

    when (val route = nav.current) {
        Route.Home -> {
            if (!BuildConfig.BUNDLED_MODELS && models is ModelsStatus.Missing) {
                OnlineSetupScreen(host.appName, rows, freeBytes, modelMessage, modelActions, onDismissMessage = graph.modelsController::clearMessage)
            } else {
                HomeScreen(
                    appName = host.appName, entries = entries, models = models, activeSet = activeSet,
                    onPickAudio = host::pickAudio,
                    onRecord = { nav.push(Route.Record) },
                    onOpen = { nav.push(Route.Transcript(it.id)) },
                    onDelete = { vm.deleteEntry(it.id) },
                    onModels = { nav.push(Route.Models) },
                    onSettings = { nav.push(Route.Settings) },
                    onSearch = if (entries.isNotEmpty()) ({ nav.push(Route.Search(null)) }) else null,
                )
            }
        }
        is Route.Transcript -> {
            val session = remember(route.entryId) { vm.session(route.entryId) }
            DisposableEffect(session) { onDispose { session.player.pause() } }
            TranscriptScreen(
                session = session,
                llmAvailability = remember(processing, route.entryId) { vm.llm.availability() },
                onBack = { nav.pop() },
                onExport = { host.export(route.entryId, it) },
                onShare = {
                    val text = vm.render(route.entryId, ExportFormat.TXT)
                    if (text != null) host.shareText(text, session.entry.value?.title ?: "Расшифровка")
                },
                onProtocol = { nav.push(Route.Protocol(route.entryId)) },
                onStartFragment = host::startFragment,
                onCancelFragment = host::cancelFragment,
                onSummary = { nav.push(Route.Summary(route.entryId)) },
                onSearch = { nav.push(Route.Search(route.entryId)) },
                focusMs = focus?.takeIf { it.entryId == route.entryId }?.ms,
                focusToken = focus?.takeIf { it.entryId == route.entryId }?.token ?: 0L,
                onFocusConsumed = { focus?.let { vm.consumeFocus(it.token) } },
            )
        }
        is Route.Summary -> {
            val session = remember(route.entryId) { vm.session(route.entryId) }
            val doc by session.doc.collectAsStateWithLifecycle()
            val state by vm.summary.state(route.entryId).collectAsStateWithLifecycle()
            val llmUi by vm.llm.ui.collectAsStateWithLifecycle()
            val caps = remember(state, llmUi.running, processing) { vm.summary.caps() }
            // LLM одна: пока на экране «Протокол» идёт генерация, выжимку не запускаем (и наоборот -- её отказ по своей стороне)
            val blocked = if (caps.llmMode && llmUi.running) "Сейчас составляется протокол — ИИ занят. Дождитесь его окончания или остановите протокол, затем запустите выжимку." else null
            // lite (extractive): выжимка мгновенная -- строим сразу при открытии, если её ещё нет
            LaunchedEffect(route.entryId, state, caps.llmMode) {
                if (state is ru.neurosteno.app.insight.SummaryState.None && !caps.llmMode) doc?.let { vm.summary.build(route.entryId, it.transcript.utterances, useLlm = false) }
            }
            SummaryScreen(
                state = state, caps = caps, blockedReason = blocked,
                names = { id -> doc?.transcript?.displayName(id) ?: ru.neurosteno.app.edit.EditableTranscript.defaultName(id) },
                actions = SummaryActions(
                    onBack = { nav.pop() },
                    onBuild = { doc?.let { vm.summary.build(route.entryId, it.transcript.utterances, useLlm = true) } },
                    onCancel = { vm.summary.cancel(route.entryId) },
                    onJump = { t, _ -> openAt(route.entryId, t) },
                    onExportTxt = { host.export(route.entryId, ExportFormat.SUMMARY_TXT) },
                    onExportPdf = { host.export(route.entryId, ExportFormat.SUMMARY_PDF) },
                ),
            )
        }
        is Route.Search -> {
            val controller = vm.search
            run {
                val ui by controller.ui.collectAsStateWithLifecycle()
                val session = remember(route.entryId) { route.entryId?.let { vm.session(it) } }
                val doc = session?.doc?.collectAsStateWithLifecycle()?.value
                val entry = session?.entry?.collectAsStateWithLifecycle()?.value
                LaunchedEffect(route) { controller.open(route.entryId) }
                SearchScreen(
                    ui = ui,
                    suggestions = remember(doc) { searchSuggestions(doc?.transcript?.speakerIds?.firstOrNull()?.let { doc.transcript.displayName(it) }) },
                    recordTitle = entry?.title,
                    actions = SearchActions(
                        onBack = { nav.pop() }, onQuery = controller::setQuery, onSubmit = controller::submit, onScope = controller::setScope,
                        onAnswer = controller::answer, onStopAnswer = controller::stopAnswer, onOpen = { rid, ms -> openAt(rid, ms) },
                    ),
                )
            }
        }
        is Route.Protocol -> {
            val session = remember(route.entryId) { vm.session(route.entryId) }
            val doc by session.doc.collectAsStateWithLifecycle()
            val entry by session.entry.collectAsStateWithLifecycle()
            LaunchedEffect(route.entryId) { vm.llm.bind(route.entryId, graph.history.readProtocol(route.entryId)) }
            ProtocolScreen(
                llm = vm.llm, transcript = doc?.transcript, title = entry?.title ?: "",
                onBack = { nav.pop() },
                onShareText = { host.shareText(it, "Протокол: " + (entry?.title ?: "")) },
                onCopy = host::copyText,
            )
        }
        Route.Models -> ModelsScreen(
            rows = rows, bundledSetId = graph.bundledSetId, freeBytes = freeBytes, message = modelMessage,
            actions = modelActions, onDismissMessage = graph.modelsController::clearMessage, onBack = { nav.pop() },
        )
        Route.Settings -> SettingsScreen(
            settings = settings, configSpellsNumbers = graph.activeConfig()?.first?.postprocess?.spellNumbers ?: false,
            activeSet = activeSet, tier = BuildConfig.TIER, versionName = BuildConfig.VERSION_NAME,
            historyBytes = remember(entries) { graph.history.usedBytes() },
            onChange = graph.settings::update,
            onModels = { nav.push(Route.Models) }, onStats = { nav.push(Route.Stats) }, onLicenses = { nav.push(Route.Licenses) },
            onClearHistory = {
                entries.forEach { vm.closeSession(it.id) }
                graph.history.clear()
            },
            onBack = { nav.pop() },
        )
        Route.Stats -> StatsScreen(
            entries = entries, deviceTotalMb = entries.maxOfOrNull { it.deviceTotalMb } ?: 0, tier = BuildConfig.TIER, onBack = { nav.pop() },
        )
        Route.Licenses -> {
            val notices = remember { ru.neurosteno.app.licenses.Notices.load(graph.appContext) }
            LicensesScreen(notices, onOpen = { nav.push(Route.LicenseText(it.id)) }, onBack = { nav.pop() })
        }
        is Route.LicenseText -> {
            val notice = remember(route.noticeId) { ru.neurosteno.app.licenses.Notices.load(graph.appContext).firstOrNull { it.id == route.noticeId } }
            if (notice == null) LaunchedEffect(Unit) { nav.pop() }
            else LicenseTextScreen(notice, remember(notice) { ru.neurosteno.app.licenses.Notices.licenseText(graph.appContext, notice) }, onBack = { nav.pop() })
        }
        Route.Record -> {
            val rec by vm.recorder.ui.collectAsStateWithLifecycle()
            val liveUi by vm.live.ui.collectAsStateWithLifecycle()
            var denied by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = rec.recording) { vm.recorder.cancel(); nav.pop() }
            DisposableEffect(Unit) { onDispose { vm.recorder.cancel() } }
            RecordScreen(
                ui = rec, live = liveUi, permissionDenied = denied,
                onStart = {
                    if (host.hasRecordPermission()) vm.recorder.start()
                    else host.requestRecordPermission { ok -> denied = !ok; if (ok) vm.recorder.start() }
                },
                onStop = {
                    val f = vm.recorder.stop()
                    if (f != null) {
                        nav.pop()
                        host.startProcessing(Uri.fromFile(f), f.name)
                    }
                },
                onOpenAppSettings = host::openAppSettings,
                onBack = { vm.recorder.cancel(); nav.pop() },
            )
        }
    }
}
