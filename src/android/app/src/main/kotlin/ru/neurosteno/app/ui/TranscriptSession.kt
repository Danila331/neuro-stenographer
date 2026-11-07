package ru.neurosteno.app.ui

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.data.ResultsAutosave
import ru.neurosteno.app.data.StoredTranscript
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.edit.SearchUtil
import ru.neurosteno.app.edit.TranscriptEditor
import ru.neurosteno.app.player.AudioPlayer
import ru.neurosteno.core.Transcript

/** Расшифровка, открытая на экране: данные, правки с отменой, поиск и плеер. Живёт в ViewModel (переживает поворот). */
class TranscriptSession(
    private val graph: AppGraph,
    val entryId: String,
) {
    /** Собственная область: после [close] ещё успевает сохранить правки, потом сама отменяется. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class Doc(val transcript: EditableTranscript, val source: Transcript)

    val player = AudioPlayer(scope, graph.appContext)

    val entry: StateFlow<HistoryEntry?> = graph.history.entries.map { list -> list.firstOrNull { it.id == entryId } }
        .stateIn(scope, SharingStarted.Eagerly, graph.history.get(entryId))

    private val _doc = MutableStateFlow<Doc?>(null)
    val doc: StateFlow<Doc?> = _doc.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val undoStack = ArrayDeque<EditableTranscript>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Номер текущего совпадения среди [matches]. */
    private val _matchCursor = MutableStateFlow(0)
    val matchCursor: StateFlow<Int> = _matchCursor.asStateFlow()

    val matches: StateFlow<List<Int>> = combine(_doc, _query) { d, q ->
        if (d == null) emptyList() else SearchUtil.matchingIndices(d.transcript.utterances.map { it.text }, q)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var saveJob: Job? = null
    /** Есть несохранённые правки. */
    @Volatile private var dirty = false

    init {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { graph.history.loadTranscript(entryId) }
            if (stored == null) {
                _loadError.value = "Не удалось прочитать расшифровку"
            } else {
                val tr = stored.toTranscript()
                _doc.value = Doc(EditableTranscript.of(tr, stored.speakerNames), tr)
            }
            val e = graph.history.get(entryId)
            player.load(e?.let { graph.history.audioFile(it) }, e?.durationMs ?: 0)
        }
    }

    // ---- поиск ----

    fun setQuery(q: String) {
        _query.value = q
        _matchCursor.value = 0
    }

    /** Следующее/предыдущее совпадение; возвращает индекс реплики для прокрутки или null. */
    fun stepMatch(delta: Int): Int? {
        val m = matches.value
        if (m.isEmpty()) return null
        val next = ((_matchCursor.value + delta) % m.size + m.size) % m.size
        _matchCursor.value = next
        return m[next]
    }

    // ---- правки ----

    fun setText(index: Int, text: String) = edit { TranscriptEditor.setText(it, index, text) }
    fun setSpeaker(index: Int, speakerId: String) = edit { TranscriptEditor.setSpeaker(it, index, speakerId) }
    fun mergeWithNext(index: Int) = edit { TranscriptEditor.mergeWithNext(it, index) }
    fun delete(index: Int) = edit { TranscriptEditor.delete(it, index) }
    fun rename(speakerId: String, name: String) = edit { TranscriptEditor.rename(it, speakerId, name) }
    fun newSpeakerId(): String = _doc.value?.let { TranscriptEditor.newSpeakerId(it.transcript) } ?: "speaker_1"

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        _canUndo.value = undoStack.isNotEmpty()
        val d = _doc.value ?: return
        _doc.value = d.copy(transcript = prev)
        dirty = true
        scheduleSave()
    }

    private fun edit(change: (EditableTranscript) -> EditableTranscript) {
        val d = _doc.value ?: return
        val next = change(d.transcript)
        if (next == d.transcript) return
        undoStack.addLast(d.transcript)
        if (undoStack.size > 50) undoStack.removeFirst()
        _canUndo.value = true
        _doc.value = d.copy(transcript = next)
        dirty = true
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            flush()
        }
    }

    /** Немедленно сохраняет правки (вызывается и при уходе с экрана). */
    suspend fun flush() = withContext(Dispatchers.IO) {
        val d = _doc.value ?: return@withContext
        if (!dirty) return@withContext
        dirty = false
        val tr = Transcript(d.transcript.utterances, d.source.meta)
        graph.history.saveTranscript(entryId, StoredTranscript.of(tr, d.transcript.speakerNames, edited = true))
        graph.history.get(entryId)?.let { e ->
            val base = ResultsAutosave.resultsDir(graph.externalFilesDir(), graph.filesDir())
            ResultsAutosave.write(base, e.sourceName, d.transcript, d.source, e.runInfo())
        }
    }

    fun audioFile(): File? = graph.history.get(entryId)?.let { graph.history.audioFile(it) }

    fun close() {
        saveJob?.cancel()
        player.release()
        scope.launch {
            runCatching { flush() }
            scope.cancel()
        }
    }
}
