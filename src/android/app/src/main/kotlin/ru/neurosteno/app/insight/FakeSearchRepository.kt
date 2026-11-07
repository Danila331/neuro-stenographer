package ru.neurosteno.app.insight

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import ru.neurosteno.core.Utterance

/** Запись для поиска: идентификатор, название, реплики и пользовательские имена говорящих (`speaker_N` -> имя). */
data class SearchRecord(val id: String, val title: String, val utterances: List<Utterance>, val speakerNames: Map<String, String> = emptyMap()) {
    fun nameOf(speakerId: String): String =
        speakerNames[speakerId]?.takeIf { it.isNotBlank() } ?: speakerId.removePrefix("speaker_").toIntOrNull()?.let { "Говорящий $it" } ?: speakerId
}

/**
 * Фейковый [SearchRepository] (debug и тесты): простой подсчёт совпавших основ слов по окнам из 3 реплик, мягкий приоритет говорящего
 * из вопроса, ответ собирается из лучших фрагментов со ссылками. Режимы -- [FakeInsightConfig]. Не порт движка.
 */
class FakeSearchRepository(private val records: () -> List<SearchRecord>) : SearchRepository {
    private val ready = MutableStateFlow<IndexState>(IndexState.Ready)
    private val building = MutableStateFlow<IndexState>(IndexState.Building(12, 40))

    override fun indexState(scope: SearchScope, recordId: String?): StateFlow<IndexState> = if (FakeInsightConfig.searchIndexBuilding) building else ready

    override fun answerCaps(): AnswerCaps = when (FakeInsightConfig.searchAnswerMode) {
        "disabled" -> AnswerCaps(enabled = false)
        "unavailable" -> AnswerCaps(enabled = true, reason = "не хватает памяти для языковой модели")
        else -> AnswerCaps(enabled = true)
    }

    override suspend fun search(query: String, scope: SearchScope, recordId: String?): List<SearchHit> {
        if (FakeInsightConfig.searchStepMs > 0) delay(FakeInsightConfig.searchStepMs * 4)
        val terms = QueryTerms.of(query)
        if (terms.isEmpty()) return emptyList()
        val wantSpeaker = Regex("(?:говорящ\\p{L}*|спикер\\p{L}*)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(query)?.groupValues?.get(1)?.let { "speaker_$it" }
        val pool = records().filter { scope == SearchScope.HISTORY || it.id == recordId }
        val hits = ArrayList<SearchHit>()
        for (rec in pool) {
            val u = rec.utterances
            for (i in u.indices) {
                val win = u.subList(i, minOf(i + 3, u.size))
                val text = win.joinToString(" ") { it.text }.lowercase().replace('ё', 'е')
                val matched = terms.count { t -> Regex("(^|[^\\p{L}])" + Regex.escape(t)).containsMatchIn(text) }
                if (matched == 0) continue
                var score = matched.toFloat() / terms.size
                if (wantSpeaker != null && win.any { it.speakerId == wantSpeaker }) score += 0.15f
                hits += SearchHit(
                    recordId = rec.id, recordTitle = rec.title, startMs = win.first().startMs, endMs = win.last().endMs,
                    lines = win.map { HitLine(it.speakerId, rec.nameOf(it.speakerId), it.startMs, it.text) },
                    score = score, terms = terms,
                )
            }
        }
        // лучшие окна без повторов по времени (окна шага 1 перекрываются)
        val picked = ArrayList<SearchHit>()
        for (h in hits.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.startMs })) {
            if (picked.none { it.recordId == h.recordId && h.startMs < it.endMs && it.startMs < h.endMs }) picked += h
            if (picked.size == 5) break
        }
        return picked.filter { it.score >= 0.5f }
    }

    override fun answer(query: String, hits: List<SearchHit>): Flow<AnswerEvent> = flow {
        if (FakeInsightConfig.searchAnswerMode == "refuse" || hits.isEmpty()) {
            delay(FakeInsightConfig.searchStepMs * 3)
            emit(AnswerEvent.Done("В найденных фрагментах этого нет."))
            return@flow
        }
        if (FakeInsightConfig.searchAnswerMode == "duplicate") {
            // как реальный сбой малой модели: предложение повторено после ссылки
            val l = hits.first().lines.first()
            val one = "${l.speakerName} сказал: ${l.text.trim().trimEnd('.')}."
            val cite = "[${clock(l.startMs)}, Говорящий ${l.speakerId.removePrefix("speaker_")}]"
            emit(AnswerEvent.Done("$one $cite $one"))
            return@flow
        }
        val top = hits.first()
        val line = top.lines.maxByOrNull { l -> top.terms.count { t -> l.text.lowercase().replace('ё', 'е').contains(t) } } ?: top.lines.first()
        fun cite(l: HitLine) = "[${clock(l.startMs)}, Говорящий ${l.speakerId.removePrefix("speaker_")}]"
        val second = hits.getOrNull(1)?.lines?.firstOrNull()
        val full = buildString {
            append("${line.speakerName} сказал: «${line.text.trim().trimEnd('.')}» ${cite(line)}.")
            if (second != null) append(" Ещё по теме: «${second.text.trim().trimEnd('.')}» ${cite(second)}.")
        }
        val words = full.split(' ')
        var acc = ""
        for (w in words) {
            acc = if (acc.isEmpty()) w else "$acc $w"
            if (FakeInsightConfig.searchStepMs > 0) delay(FakeInsightConfig.searchStepMs)
            emit(AnswerEvent.Partial(acc))
        }
        emit(AnswerEvent.Done(full))
    }

    private fun clock(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
}
