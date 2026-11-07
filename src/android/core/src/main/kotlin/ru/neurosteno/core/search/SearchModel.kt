package ru.neurosteno.core.search

import ru.neurosteno.core.summary.SUtt
import ru.neurosteno.core.summary.SummaryPlanner

/** Запись для поиска: реплики (с 0-based [SUtt.idx]) и пользовательские имена говорящих (`speaker_N` -> имя; метаданные записи). */
data class SearchRecord(
    val id: String,
    val title: String,
    val utts: List<SUtt>,
    val names: Map<String, String> = emptyMap(),
)

/**
 * Окно индекса (эталон `Chunk`). [id] -- уникальный в хранилище; [utts] -- индексы подряд идущих реплик записи; [speakers] -- говорящие
 * реплик (тот же порядок); [text] -- простой текст (BM25), [embedText] -- текст для эмбеддера (с «Говорящий N:» для окон).
 */
data class Chunk(
    val id: Long,
    val recordId: String,
    val utts: List<Int>,
    val speakers: List<String>,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val embedText: String,
) {
    /** `[мм:сс, Говорящий N]` по первой реплике. */
    val label: String get() = "[${SummaryPlanner.fmtTime(startMs)}, Говорящий ${speakers.first().substringAfter('_')}]"
}

/** Найденное окно и итоговая оценка слияния. */
data class Hit(val chunk: Chunk, val score: Double)
