package ru.neurosteno.app.data

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.export.ExportFormat
import ru.neurosteno.app.export.Exports
import ru.neurosteno.core.Transcript

/**
 * Автосохранение в `Android/data/<pkg>/files/results/` для автопроверки и `adb pull` (BACKLOG B3, B32, B33):
 *
 * * `<имя>.json` -- JSON по ТЗ; `<имя>.full.json` -- расширенный (в `meta` -- пик PSS, потоки, устройство);
 * * `<имя>.done.json` -- **маркер завершения** с метой запуска. Пишется ПОСЛЕДНИМ и атомарно (tmp -> rename): если маркер есть,
 *   оба результата уже на диске. Скрипты ждут именно его, а не строку logcat (на части устройств logcat ненадёжен);
 * * `<имя>.error.json` -- маркер сбоя с текстом ошибки (чтобы скрипт не ждал таймаут).
 *
 * Каждый шаг защищён отдельно: сбой одного файла не отменяет остальные и не глотается молча, а попадает в `problems`.
 */
object ResultsAutosave {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * Папка результатов: `<files>/results` во внешней app-specific папке; если она недоступна для записи, берётся
     * `results-<uid>`, затем внутренняя `filesDir/results`.
     *
     * Причина (B32): каталог `results` на `/sdcard/Android/data/<pkg>/files` мог остаться от прошлой установки с другим uid
     * (или быть создан `adb shell`) -- запись в него даёт `EACCES`. Раньше исключение молча глоталось, и ни файлов, ни строки
     * `done` в logcat не появлялось, хотя расшифровка в приложении была готова.
     */
    fun resultsDir(external: File?, internal: File, uid: Int = 0): File {
        val candidates = buildList {
            if (external != null) {
                add(File(external, "results"))
                add(File(external, "results-$uid"))
            }
            add(File(internal, "results"))
        }
        return candidates.firstOrNull { isWritableDir(it) } ?: candidates.last().apply { mkdirs() }
    }

    private fun isWritableDir(dir: File): Boolean = try {
        dir.mkdirs()
        val probe = File(dir, ".probe-${System.nanoTime()}")
        probe.writeText("ok")
        probe.delete()
    } catch (e: Throwable) {
        false
    }

    fun baseName(sourceName: String): String = sourceName.substringBeforeLast('.', sourceName).ifBlank { "transcript" }

    class Outcome(val files: List<String>, val problems: List<String>)

    /** Удаляет маркеры прошлого запуска с тем же именем: перед новой обработкой маркер не должен «залёживаться». */
    fun clearMarkers(dir: File, sourceName: String) {
        val base = baseName(sourceName)
        runCatching { File(dir, "$base.done.json").delete() }
        runCatching { File(dir, "$base.error.json").delete() }
    }

    /** Пишет `<имя>.json` и `<имя>.full.json`; сбой одного файла не мешает другому. */
    fun write(dir: File, sourceName: String, t: EditableTranscript, source: Transcript, run: RunInfo? = null): Outcome {
        val base = baseName(sourceName)
        val files = ArrayList<String>()
        val problems = ArrayList<String>()
        fun step(name: String, format: ExportFormat) {
            try {
                dir.mkdirs()
                writeAtomic(File(dir, name), Exports.render(format, t, source, run))
                files += name
            } catch (e: Throwable) {
                problems += "$name: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        step("$base.json", ExportFormat.JSON)
        step("$base.full.json", ExportFormat.FULL_JSON)
        return Outcome(files, problems)
    }

    /** Маркер успешного завершения; null, если записать не удалось (причина -- в возвращаемом [Outcome] не теряется: вызывающий логирует). */
    fun writeDoneMarker(
        dir: File,
        sourceName: String,
        source: Transcript,
        utterances: Int,
        run: RunInfo,
        modelSetId: String?,
        warnings: List<String>,
        outcome: Outcome,
        finishedAtMs: Long = System.currentTimeMillis(),
        /** Дополнительные поля маркера (например, `fragment` -- сведения о пересчёте фрагмента). */
        extras: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ): Result<File> = runCatching {
        val m = source.meta
        val json = buildJsonObject {
            put("status", "done")
            put("source", sourceName)
            put("finished_at_ms", finishedAtMs)
            put("audio_duration_ms", m.audioDurationMs)
            put("processing_ms", m.processingMs)
            put("rtf", Math.round(m.rtf * 1000.0) / 1000.0)
            put("language", m.language)
            put("utterances", utterances)
            put("tier", m.tier)
            put("model_set", modelSetId ?: "")
            put("stage_ms", buildJsonObject { m.stageMs.forEach { (k, v) -> put(k, v) } })
            put("peak_pss_mb", run.peakPssMb)
            put("threads", run.threads)
            put("device_model", run.device)
            put("android_sdk", run.androidSdk)
            put("total_ram_mb", run.totalRamMb)
            put("app_version", run.appVersion.ifBlank { m.appVersion })
            put("results_dir", dir.absolutePath)
            put("files", JsonArray(outcome.files.map { JsonPrimitive(it) }))
            put("problems", JsonArray(outcome.problems.map { JsonPrimitive(it) }))
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            extras.forEach { (k, v) -> put(k, v) }
        }
        val file = File(dir, "${baseName(sourceName)}.done.json")
        writeAtomic(file, pretty.encodeToString(JsonObject.serializer(), json))
        file
    }

    fun writeErrorMarker(dir: File, sourceName: String, message: String, run: RunInfo? = null, finishedAtMs: Long = System.currentTimeMillis()): Result<File> = runCatching {
        dir.mkdirs()
        val json = buildJsonObject {
            put("status", "error")
            put("source", sourceName)
            put("finished_at_ms", finishedAtMs)
            put("error", message)
            if (run != null) {
                put("peak_pss_mb", run.peakPssMb)
                put("device_model", run.device)
                put("android_sdk", run.androidSdk)
                put("app_version", run.appVersion)
            }
        }
        val file = File(dir, "${baseName(sourceName)}.error.json")
        writeAtomic(file, pretty.encodeToString(JsonObject.serializer(), json))
        file
    }

    /** Строка logcat (тег `Neurosteno`); префикс `done <имя>:` сохранён для совместимости со старыми скриптами. */
    fun doneLine(sourceName: String, source: Transcript, run: RunInfo, warnings: List<String>, problems: List<String>, dir: File? = null): String {
        val m = source.meta
        return "done $sourceName: duration=${m.audioDurationMs} ms processing=${m.processingMs} ms " +
            "rtf=${String.format(Locale.ROOT, "%.3f", m.rtf)} lang=${m.language} stages=${m.stageMs} " +
            "peak_pss_mb=${run.peakPssMb} threads=${run.threads} device=${run.device} sdk=${run.androidSdk} app=${run.appVersion.ifBlank { m.appVersion }} " +
            "warnings=$warnings" + (dir?.let { " results=${it.absolutePath}" } ?: "") + if (problems.isEmpty()) "" else " write_problems=$problems"
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
