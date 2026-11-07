package ru.neurosteno.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ru.neurosteno.app.NeuroApp
import ru.neurosteno.app.export.ExportFormat
import ru.neurosteno.app.export.Exports
import ru.neurosteno.app.llm.LlmController

/** Общее состояние интерфейса: открытые расшифровки (с плеером и правками), LLM, запись. Переживает поворот экрана. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as NeuroApp).graph
    val llm = LlmController(app, graph)
    val live = ru.neurosteno.app.live.LiveController(app, graph)
    val recorder = RecorderController(app, live)

    /** Выжимка (B50): порт поверх `AppGraph.summary`. */
    val summary: ru.neurosteno.app.insight.SummaryRepository get() = graph.summaryRepository

    /** Поиск (B51): контроллер экрана поверх `AppGraph.searchRepository`. */
    val search: ru.neurosteno.app.insight.SearchController = ru.neurosteno.app.insight.SearchController(graph.searchRepository, viewModelScope)

    /**
     * Просьба открыть реплику: из выжимки или поиска -- расшифровка [entryId] прокручивается к реплике на времени [ms] и плеер встаёт
     * на него. [token] делает каждую просьбу уникальной (повторный переход к тому же месту тоже срабатывает).
     */
    data class FocusRequest(val entryId: String, val ms: Long, val token: Long)

    private val _focus = kotlinx.coroutines.flow.MutableStateFlow<FocusRequest?>(null)
    val focus: kotlinx.coroutines.flow.StateFlow<FocusRequest?> = _focus
    private var focusToken = 0L

    fun requestFocus(entryId: String, ms: Long) { _focus.value = FocusRequest(entryId, ms, ++focusToken) }
    fun consumeFocus(token: Long) { if (_focus.value?.token == token) _focus.value = null }

    private val sessions = HashMap<String, TranscriptSession>()

    /** Формат, ожидающий результата системного диалога «Сохранить как». */
    var pendingExport: Pair<String, ExportFormat>? = null

    fun session(entryId: String): TranscriptSession = sessions.getOrPut(entryId) { TranscriptSession(graph, entryId) }

    /** Явная пауза воспроизведения при уходе в фон: Android 17 глушит фоновый звук, лучше остановиться самим (B34). */
    fun pausePlayback() {
        sessions.values.forEach { it.player.pause() }
    }

    fun closeSession(entryId: String) {
        sessions.remove(entryId)?.close()
    }

    /** Оставляет открытыми только сессии записей [transcripts]; LLM выгружается, когда экран протокола закрыт. */
    fun retain(transcripts: Set<String>, protocolOpen: Boolean) {
        sessions.keys.filter { it !in transcripts }.forEach { closeSession(it) }
        if (!protocolOpen) llm.release()
    }

    fun deleteEntry(entryId: String) {
        closeSession(entryId)
        graph.history.delete(entryId)
    }

    /** Текст файла в выбранном формате для открытой расшифровки; null, если она ещё не загружена. */
    fun render(entryId: String, format: ExportFormat): String? {
        val doc = sessions[entryId]?.doc?.value ?: return null
        return Exports.render(format, doc.transcript, doc.source, graph.history.get(entryId)?.runInfo())
    }

    /** Запись файла в место, выбранное пользователем (SAF). Возвращает сообщение об ошибке или null. */
    fun writeExport(uri: Uri, entryId: String, format: ExportFormat): String? {
        if (format == ExportFormat.PDF) return writePdf(uri, entryId)
        if (format == ExportFormat.SUMMARY_TXT || format == ExportFormat.SUMMARY_PDF) return writeSummary(uri, entryId, format)
        val text = render(entryId, format) ?: return "Расшифровка ещё не загружена"
        return runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            null
        }.getOrElse { "Не удалось сохранить файл: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** PDF: шапка, легенда говорящих, реплики и протокол (если уже составлен). Нативный PdfDocument, без сети. */
    private fun writePdf(uri: Uri, entryId: String): String? {
        val doc = sessions[entryId]?.doc?.value ?: return "Расшифровка ещё не загружена"
        val model = ru.neurosteno.app.export.PdfModelBuilder.build(graph.history.get(entryId), doc.transcript, graph.history.readProtocol(entryId), summary = graph.summary.load(entryId))
        return runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { ru.neurosteno.app.export.PdfExporter(getApplication()).write(it, model) }
            null
        }.getOrElse { "Не удалось сохранить PDF: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** Выжимка в TXT или PDF (B50); берётся сохранённая/текущая выжимка записи. */
    private fun writeSummary(uri: Uri, entryId: String, format: ExportFormat): String? {
        val doc = sessions[entryId]?.doc?.value ?: return "Расшифровка ещё не загружена"
        val shown = summary.state(entryId).value
        val sum = (shown as? ru.neurosteno.app.insight.SummaryState.Shown)?.doc ?: (shown as? ru.neurosteno.app.insight.SummaryState.Failed)?.doc
            ?: return "Выжимки ещё нет: сначала составьте её."
        return runCatching {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!
            out.use {
                if (format == ExportFormat.SUMMARY_TXT) {
                    it.write(ru.neurosteno.app.export.SummaryExport.toText(sum, doc.transcript, graph.history.get(entryId)).toByteArray(Charsets.UTF_8))
                } else {
                    val model = ru.neurosteno.app.export.PdfModelBuilder.buildSummaryOnly(graph.history.get(entryId), doc.transcript, sum)
                    ru.neurosteno.app.export.PdfExporter(getApplication()).write(it, model)
                }
            }
            null
        }.getOrElse { "Не удалось сохранить файл: ${it.message ?: it.javaClass.simpleName}" }
    }

    override fun onCleared() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        llm.release()
        recorder.cancel()
        search.close()
    }
}
