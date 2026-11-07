package ru.neurosteno.core.search

import ru.neurosteno.core.summary.SummaryPlanner

/** Ссылка из ответа: запись, секунда начала реплики и говорящий. */
data class Citation(val recordId: String, val startSec: Long, val speakerId: String)

/** Результат проверки ответа: текст без недействительных ссылок, действительные ссылки, число удалённых, признак отказа. */
data class VerifiedAnswer(val text: String, val validCites: List<Citation>, val badCites: Int, val refused: Boolean)

/**
 * Контекст для ответа LLM и проверка ссылок (порт `bench_rag/answer.py`). Контекст -- строки `[мм:сс, Говорящий N] текст` по записям
 * и времени, не длиннее [maxChars]; ссылки `[мм:сс, Говорящий N]` вне контекста из ответа удаляются ([verify]).
 */
object AnswerBuilder {
    const val NO_ANSWER = "В найденных фрагментах этого нет."
    private val CITE = Regex("\\[([0-9]{1,3}):([0-9]{2}),[\\s\\p{Z}]*Говорящий[\\s\\p{Z}]*([0-9]+)\\]")
    private val SPACE_BEFORE_PUNCT = Regex("[\\s\\p{Z}]+([.,;])")

    class Context(val text: String, val cites: List<Citation>)

    fun context(hits: List<Hit>, records: Map<String, SearchRecord>, maxChars: Int = 3200): Context {
        val byRec = LinkedHashMap<String, MutableList<Hit>>()
        for (h in hits) byRec.getOrPut(h.chunk.recordId) { ArrayList() } += h
        val multi = byRec.size > 1
        val out = ArrayList<String>()
        val cites = ArrayList<Citation>()
        var n = 0
        for ((rid, hs) in byRec) {
            val rec = records.getValue(rid)
            if (multi) out += "Запись: ${rec.title.ifEmpty { rid }}"
            val seen = HashSet<Int>()
            for (h in hs.sortedBy { it.chunk.startMs }) {
                for (u in h.chunk.utts) {
                    if (!seen.add(u)) continue
                    val ut = rec.utts[u]
                    val ln = "[${SummaryPlanner.fmtTime(ut.startMs)}, Говорящий ${ut.spk}] ${ut.text}"
                    if (n + ln.codePointCount(0, ln.length) > maxChars) break
                    n += ln.codePointCount(0, ln.length) + 1
                    out += ln
                    cites += Citation(rid, ut.startMs / 1000, ut.speakerId)
                }
            }
        }
        return Context(out.joinToString("\n"), cites)
    }

    /** Пользовательская часть запроса. */
    fun userMessage(ctx: Context, question: String) = "Фрагменты:\n${ctx.text}\n\nВопрос: $question"

    /** Удаляет ссылки, не указывающие на фрагмент контекста (время с точностью до 1 с и тот же говорящий). */
    fun verify(text: String, cites: List<Citation>): VerifiedAnswer {
        val ok = ArrayList<Citation>()
        var bad = 0
        val replaced = CITE.replace(text) { m ->
            val s = m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()
            val spk = "speaker_${m.groupValues[3]}"
            val c = cites.firstOrNull { it.speakerId == spk && kotlin.math.abs(it.startSec - s) <= 1 }
            if (c != null) { ok += c; m.value } else { bad++; "" }
        }
        val clean = SPACE_BEFORE_PUNCT.replace(replaced, "$1").trim()
        return VerifiedAnswer(clean, ok, bad, NO_ANSWER.take(20).lowercase() in text.lowercase())
    }
}
