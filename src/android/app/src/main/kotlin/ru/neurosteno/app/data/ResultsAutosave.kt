package ru.neurosteno.app.data

import java.io.File
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.export.ExportFormat
import ru.neurosteno.app.export.Exports
import ru.neurosteno.core.Transcript

/**
 * Автосохранение в `Android/data/<pkg>/files/results/<имя>.json` (+ `.full.json`) -- для автопроверки и adb pull
 * (BACKLOG B3). После правок пользователя файлы перезаписываются, чтобы совпадать с тем, что показано на экране.
 */
object ResultsAutosave {
    fun resultsDir(base: File?, fallback: File): File = File(base ?: fallback, "results").apply { mkdirs() }

    fun write(dir: File, sourceName: String, t: EditableTranscript, source: Transcript) {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "transcript" }
        runCatching {
            File(dir, "$base.json").writeText(Exports.render(ExportFormat.JSON, t, source), Charsets.UTF_8)
            File(dir, "$base.full.json").writeText(Exports.render(ExportFormat.FULL_JSON, t, source), Charsets.UTF_8)
        }
    }
}
