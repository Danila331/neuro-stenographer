package ru.neurosteno.app.data

import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.neurosteno.core.Transcript

/** Запись истории (файл `entry.json`): всё, что нужно списку, статистике и карточке, без загрузки самой расшифровки. */
@Serializable
data class HistoryEntry(
    val id: String,
    /** Имя исходного файла (как показал SAF), с расширением. */
    val sourceName: String,
    val createdAt: Long,
    val durationMs: Long,
    val speakerCount: Int,
    val utteranceCount: Int,
    val preview: String,
    val language: String = "ru",
    val tier: String = "",
    val modelSetId: String? = null,
    val processingMs: Long = 0,
    val stageMs: Map<String, Long> = emptyMap(),
    /** Пик PSS процесса во время обработки, МБ (0 -- не измерялся). */
    val peakPssMb: Int = 0,
    val deviceTotalMb: Int = 0,
    val warnings: List<String> = emptyList(),
    /** Число потоков обработки (из плана ресурсов) и устройство -- для статистики и расширенного JSON (B33). */
    val threads: Int = 0,
    val device: String = "",
    val androidSdk: Int = 0,
    val appVersion: String = "",
    /** Имя файла с копией аудио в каталоге записи (для встроенного плеера); null -- копия не сохранилась. */
    val audioFile: String? = null,
    val edited: Boolean = false,
) {
    val title: String get() = sourceName.substringBeforeLast('.', sourceName).ifBlank { sourceName }
    val rtf: Double get() = if (durationMs > 0 && processingMs > 0) processingMs.toDouble() / durationMs else 0.0

    fun runInfo() = RunInfo(peakPssMb, threads, device, androidSdk, deviceTotalMb, appVersion)
}

/**
 * Сведения о запуске обработки: пик PSS процесса, число потоков, устройство. Пишутся в `meta` расширенного JSON,
 * в маркер `<имя>.done.json`, в строку `done …` logcat и в историю приложения (BACKLOG B33).
 */
data class RunInfo(
    val peakPssMb: Int = 0,
    val threads: Int = 0,
    val device: String = "",
    val androidSdk: Int = 0,
    val totalRamMb: Int = 0,
    val appVersion: String = "",
)

/**
 * История расшифровок в приватном каталоге приложения: `history/<id>/{entry.json, transcript.json, audio.<ext>, protocol.txt, summary.json}`.
 * Всё -- обычные файлы без базы данных; чтение/запись под одним замком, список наблюдаемый ([entries]).
 */
class HistoryRepository(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        val list = synchronized(lock) {
            (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { readEntry(it) }
        }.sortedByDescending { it.createdAt }
        _entries.value = list
    }

    fun get(id: String): HistoryEntry? = _entries.value.firstOrNull { it.id == id }

    fun dir(id: String): File = File(root, safeId(id))

    fun audioFile(entry: HistoryEntry): File? = entry.audioFile?.let { File(dir(entry.id), it) }?.takeIf { it.isFile }

    /**
     * Создаёт запись для готовой расшифровки. [audio] -- источник аудио (копируется в каталог записи; сбой копирования не
     * мешает сохранению расшифровки, просто плеера не будет).
     */
    fun add(
        sourceName: String,
        transcript: Transcript,
        warnings: List<String> = emptyList(),
        run: RunInfo = RunInfo(),
        modelSetId: String? = null,
        audio: (() -> InputStream)? = null,
    ): HistoryEntry = synchronized(lock) {
        val created = clock()
        val id = newId(created)
        val dir = dir(id).apply { mkdirs() }
        val audioName = audio?.let { open ->
            val ext = sourceName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: "audio"
            val target = File(dir, "audio.$ext")
            runCatching { open().use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } } }
                .fold({ target.name }, { target.delete(); null })
        }
        val stored = StoredTranscript.of(transcript)
        File(dir, TRANSCRIPT).writeText(stored.encode(), Charsets.UTF_8)
        val entry = describe(id, sourceName, created, stored, warnings, run, modelSetId, audioName)
        File(dir, ENTRY).writeText(JSON.encodeToString(HistoryEntry.serializer(), entry), Charsets.UTF_8)
        publish(entry)
        entry
    }

    fun loadTranscript(id: String): StoredTranscript? = synchronized(lock) {
        runCatching { StoredTranscript.decode(File(dir(id), TRANSCRIPT).readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** Сохраняет правки (реплики, имена говорящих) и обновляет карточку в списке. */
    fun saveTranscript(id: String, stored: StoredTranscript): HistoryEntry? = synchronized(lock) {
        val old = get(id) ?: return null
        File(dir(id), TRANSCRIPT).writeText(stored.encode(), Charsets.UTF_8)
        val entry = describe(id, old.sourceName, old.createdAt, stored, old.warnings, old.runInfo(), old.modelSetId, old.audioFile)
            .copy(processingMs = old.processingMs, stageMs = old.stageMs, tier = old.tier, language = old.language, edited = stored.edited)
        File(dir(id), ENTRY).writeText(JSON.encodeToString(HistoryEntry.serializer(), entry), Charsets.UTF_8)
        publish(entry)
        entry
    }

    fun readProtocol(id: String): String? = synchronized(lock) { File(dir(id), PROTOCOL).takeIf { it.isFile }?.readText(Charsets.UTF_8) }

    fun writeProtocol(id: String, text: String) = synchronized(lock) {
        if (dir(id).isDirectory) File(dir(id), PROTOCOL).writeText(text, Charsets.UTF_8)
    }

    /** `summary.json` записи (B50): [ru.neurosteno.core.summary.SummaryDoc] в JSON; null -- выжимки ещё нет. */
    fun readSummary(id: String): String? = synchronized(lock) { File(dir(id), SUMMARY).takeIf { it.isFile }?.readText(Charsets.UTF_8) }

    /** Запись атомарна (tmp -> rename): UI может читать файл, пока выжимка дописывается по фрагментам. */
    fun writeSummary(id: String, json: String) = synchronized(lock) {
        val d = dir(id)
        if (d.isDirectory) {
            val tmp = File(d, "$SUMMARY.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(File(d, SUMMARY))) { File(d, SUMMARY).writeText(json, Charsets.UTF_8); tmp.delete() }
        }
    }

    /** Расшифровка до пересчёта фрагмента -- для «Отменить» (один уровень). */
    fun writeUndo(id: String, stored: StoredTranscript) = synchronized(lock) {
        if (dir(id).isDirectory) File(dir(id), UNDO).writeText(stored.encode(), Charsets.UTF_8)
    }

    fun readUndo(id: String): StoredTranscript? = synchronized(lock) {
        runCatching { StoredTranscript.decode(File(dir(id), UNDO).readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun clearUndo(id: String) = synchronized(lock) { runCatching { File(dir(id), UNDO).delete() }; Unit }

    fun delete(id: String) {
        synchronized(lock) { dir(id).deleteRecursively() }
        _entries.value = _entries.value.filterNot { it.id == id }
    }

    fun clear() {
        synchronized(lock) { root.listFiles()?.forEach { it.deleteRecursively() } }
        _entries.value = emptyList()
    }

    /** Занято на диске, байт. */
    fun usedBytes(): Long = synchronized(lock) { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    // ---- внутреннее ----

    private fun describe(
        id: String, sourceName: String, created: Long, stored: StoredTranscript, warnings: List<String>,
        run: RunInfo, modelSetId: String?, audioFile: String?,
    ): HistoryEntry {
        val preview = stored.utterances.firstOrNull()?.text?.let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS).trimEnd() + "…" else it } ?: ""
        return HistoryEntry(
            id = id, sourceName = sourceName, createdAt = created, durationMs = stored.meta.audioDurationMs,
            speakerCount = stored.utterances.map { it.speaker }.distinct().size, utteranceCount = stored.utterances.size,
            preview = preview, language = stored.meta.language, tier = stored.meta.tier, modelSetId = modelSetId,
            processingMs = stored.meta.processingMs, stageMs = stored.meta.stageMs, peakPssMb = run.peakPssMb,
            deviceTotalMb = run.totalRamMb, warnings = warnings, threads = run.threads, device = run.device,
            androidSdk = run.androidSdk, appVersion = run.appVersion, audioFile = audioFile, edited = stored.edited,
        )
    }

    private fun publish(entry: HistoryEntry) {
        _entries.value = (_entries.value.filterNot { it.id == entry.id } + entry).sortedByDescending { it.createdAt }
    }

    private fun readEntry(dir: File): HistoryEntry? =
        runCatching { JSON.decodeFromString(HistoryEntry.serializer(), File(dir, ENTRY).readText(Charsets.UTF_8)) }.getOrNull()

    private fun newId(created: Long): String {
        var id = created.toString(36)
        var n = 0
        while (File(root, id).exists()) id = created.toString(36) + "-" + (++n)
        return id
    }

    private fun safeId(id: String): String {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' }) { "Недопустимый id записи" }
        return id
    }

    companion object {
        private const val ENTRY = "entry.json"
        private const val TRANSCRIPT = "transcript.json"
        private const val PROTOCOL = "protocol.txt"
        private const val SUMMARY = "summary.json"
        private const val UNDO = "transcript.undo.json"
        private const val PREVIEW_CHARS = 140
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
