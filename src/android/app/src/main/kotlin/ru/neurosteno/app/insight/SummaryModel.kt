package ru.neurosteno.app.insight

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Схема `summary.json` (B50, `src/eval/bench_rag/ANDROID_SPEC.md` §3) -- то, что рисует экран «Выжимка». Поля совпадают с моделью движка
// `ru.neurosteno.core.summary` (SummaryDoc): когда движок будет слит, эти классы заменяются импортами оттуда без правок UI.

/** Ссылка на реплику: [n] -- номер реплики с 1, [t] -- её start_ms (время всегда берётся из реплики), [speaker] -- `speaker_N`. */
@Serializable
data class SummaryRef(val n: Int, val t: Long, val speaker: String)

@Serializable
data class SummaryPoint(val speaker: String, val text: String, val refs: List<SummaryRef> = emptyList())

@Serializable
data class SummaryDecision(val text: String, val refs: List<SummaryRef> = emptyList())

/** Задача; [who] -- исполнитель, если назван (иначе пусто). */
@Serializable
data class SummaryTask(val who: String = "", val text: String, val refs: List<SummaryRef> = emptyList())

@Serializable
data class SummaryTopic(
    val title: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val points: List<SummaryPoint> = emptyList(),
    val decisions: List<SummaryDecision> = emptyList(),
    val tasks: List<SummaryTask> = emptyList(),
)

/**
 * Выжимка записи. [mode] -- `extractive` (мгновенная, без LLM) | `llm`; [done] = false -- LLM ещё работает (частичный результат:
 * [fragmentsDone] из [fragments]), UI показывает то, что есть, и обновляется.
 */
@Serializable
data class SummaryDoc(
    val schema: Int = 1,
    val mode: String = MODE_EXTRACTIVE,
    val model: String = "",
    val done: Boolean = true,
    val fragments: Int = 0,
    @SerialName("fragments_done") val fragmentsDone: Int = 0,
    val overview: String = "",
    val topics: List<SummaryTopic> = emptyList(),
) {
    val isLlm: Boolean get() = mode == MODE_LLM
    val isEmpty: Boolean get() = topics.isEmpty() && overview.isBlank()

    companion object {
        const val MODE_EXTRACTIVE = "extractive"
        const val MODE_LLM = "llm"
    }
}

object SummaryJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = true; prettyPrint = true }

    /** Разбор `summary.json`; битый JSON -> `null` (UI покажет «составить заново», а не упадёт). */
    fun parse(text: String): SummaryDoc? = runCatching { json.decodeFromString(SummaryDoc.serializer(), text) }.getOrNull()

    fun encode(doc: SummaryDoc): String = json.encodeToString(SummaryDoc.serializer(), doc)
}

/** Первая ссылка пункта (по ней тап ведёт к реплике); у пункта без ссылок -- `null`. */
fun List<SummaryRef>.firstRef(): SummaryRef? = minByOrNull { it.t }
