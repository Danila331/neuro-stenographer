package ru.neurosteno.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
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
        val text = render(entryId, format) ?: return "Расшифровка ещё не загружена"
        return runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            null
        }.getOrElse { "Не удалось сохранить файл: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** PDF: шапка, легенда говорящих, реплики и протокол (если уже составлен). Нативный PdfDocument, без сети. */
    private fun writePdf(uri: Uri, entryId: String): String? {
        val doc = sessions[entryId]?.doc?.value ?: return "Расшифровка ещё не загружена"
        val model = ru.neurosteno.app.export.PdfModelBuilder.build(graph.history.get(entryId), doc.transcript, graph.history.readProtocol(entryId))
        return runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { ru.neurosteno.app.export.PdfExporter(getApplication()).write(it, model) }
            null
        }.getOrElse { "Не удалось сохранить PDF: ${it.message ?: it.javaClass.simpleName}" }
    }

    override fun onCleared() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        llm.release()
        recorder.cancel()
    }
}
