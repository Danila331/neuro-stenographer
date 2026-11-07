package ru.neurosteno.core.summary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Реплика для выжимки: [idx] -- 0-based номер в расшифровке, [spk] -- номер говорящего из `speaker_N`. */
data class SUtt(val idx: Int, val speakerId: String, val spk: Int, val startMs: Long, val endMs: Long, val text: String)

data class Point(val speaker: Int, val text: String, val refs: List<Int>)
data class Item(val text: String, val refs: List<Int>, val who: String = "")

/** Тема: [start]/[end] -- 0-based индексы реплик (включительно). */
class Topic(
    var title: String,
    var start: Int,
    var end: Int,
    var points: MutableList<Point> = ArrayList(),
    var decisions: MutableList<Item> = ArrayList(),
    var tasks: MutableList<Item> = ArrayList(),
    var centroid: DoubleArray? = null,
)

/** Счётчики проверки (эталон `Stats`). */
class SummaryStats {
    var refsTotal = 0
    var refsBadRange = 0
    var refsBadSpeaker = 0
    var relinked = 0
    var itemsTotal = 0
    var itemsDropped = 0
    var parseFailed = 0
    override fun toString() = "refs=$refsTotal bad_range=$refsBadRange bad_speaker=$refsBadSpeaker relinked=$relinked " +
        "items=$itemsTotal dropped=$itemsDropped parse_failed=$parseFailed"
}

// ---------------------------------------------------------------- схема summary.json (то, что рисует UI) -----------------------------
/** Ссылка на реплику: [n] -- номер реплики с 1, [t] -- её start_ms, [speaker] -- `speaker_N`. Время берётся из реплики, не из текста модели. */
@Serializable
data class SummaryRef(val n: Int, val t: Long, val speaker: String)

@Serializable
data class SummaryPoint(val speaker: String, val text: String, val refs: List<SummaryRef>)

@Serializable
data class SummaryDecision(val text: String, val refs: List<SummaryRef>)

@Serializable
data class SummaryTask(val who: String, val text: String, val refs: List<SummaryRef>)

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
 * `<имя>.summary.json`. [mode] -- `extractive` (мгновенная выжимка без LLM) | `llm`. [done] = false -- LLM ещё работает
 * (частичный результат: [fragmentsDone] из [fragments]); UI показывает extractive/частичную версию и обновляется.
 */
@Serializable
data class SummaryDoc(
    val schema: Int = 1,
    val mode: String,
    val model: String = "",
    val done: Boolean = true,
    val fragments: Int = 0,
    @SerialName("fragments_done") val fragmentsDone: Int = 0,
    val overview: String = "",
    val topics: List<SummaryTopic> = emptyList(),
)
