package ru.neurosteno.app.llm

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.neurosteno.app.data.ResultsAutosave

/** Измерения одного запроса протокола или ответа на вопрос. */
data class LlmRunMetrics(
    val kind: String,
    val model: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokPerSecond: Double,
    val durationMs: Long,
    val peakPssMb: Int,
    val status: String,
    /** Фактически применённые параметры llama.cpp (`no_repack=1 mmap=1 flash_attn=enabled kv=q8_0 ...`), B48. */
    val options: String = "",
)

/** Форматирование строки logcat и маркера `<source>.llm.json` (B43). */
object LlmDiagnostics {
    fun doneLine(run: LlmRunMetrics): String =
        "llm_done kind=${run.kind} model=${run.model} prompt_tokens=${run.promptTokens} " +
            "gen_tokens=${run.generatedTokens} tok_per_s=${String.format(Locale.ROOT, "%.2f", run.tokPerSecond)} " +
            "ms=${run.durationMs} peak_pss_mb=${run.peakPssMb} status=${run.status}" +
            if (run.options.isBlank()) "" else " llama_opts=\"${run.options}\""

    fun writeMarker(dir: File, sourceName: String, run: LlmRunMetrics, finishedAtMs: Long = System.currentTimeMillis()): Result<File> = runCatching {
        dir.mkdirs()
        val json = buildJsonObject {
            put("source", sourceName)
            put("finished_at_ms", finishedAtMs)
            put("kind", run.kind)
            put("model", run.model)
            put("prompt_tokens", run.promptTokens)
            put("prompt_tokens_estimated", true)
            put("gen_tokens", run.generatedTokens)
            put("tok_per_s", run.tokPerSecond)
            put("ms", run.durationMs)
            put("peak_pss_mb", run.peakPssMb)
            put("status", run.status)
            if (run.options.isNotBlank()) put("llama_options", run.options)
        }
        val target = File(dir, "${ResultsAutosave.baseName(sourceName)}.llm.json")
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString(), Charsets.UTF_8)
            tmp.delete()
        }
        target
    }
}
