package ru.neurosteno.app.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.core.SrtExporter
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptJson
import ru.neurosteno.core.TxtExporter
import ru.neurosteno.core.VttExporter

/** Форматы экспорта. [JSON] -- строго формат ТЗ (без имён говорящих), остальные могут содержать имена. */
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    JSON("Экспорт JSON", "json", "application/json"),
    TXT("Текст (TXT)", "txt", "text/plain"),
    SRT("Субтитры (SRT)", "srt", "application/x-subrip"),
    VTT("Субтитры (VTT)", "vtt", "text/vtt"),
    FULL_JSON("Расширенный JSON", "full.json", "application/json"),
}

object Exports {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun transcriptOf(t: EditableTranscript, source: Transcript): Transcript =
        Transcript(t.utterances, source.meta)

    /**
     * Текст файла выбранного формата. Идентификаторы в [t] канонические (TranscriptEditor.canonical), поэтому имена
     * применяются напрямую. JSON по ТЗ имён не содержит -- только `speaker_N`.
     */
    fun render(format: ExportFormat, t: EditableTranscript, source: Transcript): String {
        val tr = transcriptOf(t, source)
        val names: (String) -> String = { t.displayName(it) }
        return when (format) {
            ExportFormat.JSON -> TranscriptJson.toJson(tr)
            ExportFormat.TXT -> TxtExporter(names).export(tr)
            ExportFormat.SRT -> SrtExporter(names).export(tr)
            ExportFormat.VTT -> VttExporter(names).export(tr)
            ExportFormat.FULL_JSON -> fullJsonWithSpeakers(t, tr)
        }
    }

    /** `toFullJson` из :core + ключ `speakers` (id -> отображаемое имя); основной блок `transcript` не меняется. */
    private fun fullJsonWithSpeakers(t: EditableTranscript, tr: Transcript): String {
        val base = Json.parseToJsonElement(TranscriptJson.toFullJson(tr)) as JsonObject
        val speakers = buildJsonObject { t.speakerIds.forEach { put(it, t.displayName(it)) } }
        val merged = JsonObject(LinkedHashMap(base).apply { put("speakers", speakers) })
        return pretty.encodeToString(JsonObject.serializer(), merged)
    }

    /** Имя файла: имя записи без расширения + расширение формата; недопустимые символы заменяются. */
    fun fileName(title: String, format: ExportFormat): String {
        val base = title.substringBeforeLast('.', title).replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifBlank { "transcript" }
        return "$base.${format.extension}"
    }
}
