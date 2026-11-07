package ru.neurosteno.app.insight

import kotlinx.serialization.json.Json
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryRef

// Схема `summary.json` (B50) -- классы движка `ru.neurosteno.core.summary` (SummaryDoc, SummaryTopic, SummaryPoint, SummaryDecision, SummaryTask,
// SummaryRef); здесь только расширения для UI.

const val SUMMARY_MODE_EXTRACTIVE = "extractive"
const val SUMMARY_MODE_LLM = "llm"

val SummaryDoc.isLlm: Boolean get() = mode == SUMMARY_MODE_LLM
val SummaryDoc.isEmpty: Boolean get() = topics.isEmpty() && overview.isBlank()

object SummaryJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = true; prettyPrint = true }

    /** Разбор `summary.json`; битый JSON -> `null` (UI покажет «составить заново», а не упадёт). Нет `mode` -- быстрая версия. */
    fun parse(text: String): SummaryDoc? = runCatching {
        val el = json.parseToJsonElement(text)
        val obj = el as? kotlinx.serialization.json.JsonObject ?: return null
        val withMode = if ("mode" in obj) obj else kotlinx.serialization.json.JsonObject(obj + ("mode" to kotlinx.serialization.json.JsonPrimitive(SUMMARY_MODE_EXTRACTIVE)))
        json.decodeFromJsonElement(SummaryDoc.serializer(), withMode)
    }.getOrNull()

    fun encode(doc: SummaryDoc): String = json.encodeToString(SummaryDoc.serializer(), doc)
}

/** Первая ссылка пункта (по ней тап ведёт к реплике); у пункта без ссылок -- `null`. */
fun List<SummaryRef>.firstRef(): SummaryRef? = minByOrNull { it.t }
