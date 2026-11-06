package ru.neurosteno.core

/**
 * Выравнивание слов по говорящим (ARCHITECTURE §5). Чистая детерминированная функция;
 * эталон -- Python (`src/eval/refpipe`), здесь порт 1:1.
 *
 * Детали, которые контракт оставляет открытыми (зафиксированы здесь и в тестах):
 *  - слова сортируются по (start, end) стабильно; пустые слова отбрасываются;
 *  - при равенстве пересечений/расстояний побеждает меньший номер спикера;
 *  - «остров» -- максимальная последовательная серия слов одного спикера длиной <= minIslandWords,
 *    стоящая между сериями ОДНОГО И ТОГО ЖЕ другого спикера, с длительностью (от начала первого до
 *    конца последнего слова) < minIslandMs; переназначается, только если у КАЖДОГО слова острова
 *    пересечение с лучшим turn'ом его спикера < 50 % длины слова; повторяется до неподвижной точки.
 */
class Aligner(private val config: AlignConfig = AlignConfig()) {

    fun align(words: List<Word>, turns: List<SpeakerTurn>): List<Utterance> {
        val ws = words
            .filter { it.text.isNotBlank() }
            .map { it.copy(text = it.text.trim()) }
            .sortedWith(compareBy<Word>({ it.startMs }, { it.endMs }))
        if (ws.isEmpty()) return emptyList()

        val speakers = IntArray(ws.size) { assignSpeaker(ws[it], turns) }
        smooth(ws, turns, speakers)
        return renumber(group(ws, speakers))
    }

    // --- шаг 1 ---
    private fun assignSpeaker(w: Word, turns: List<SpeakerTurn>): Int {
        if (turns.isEmpty()) return 0
        var best = -1
        var bestOverlap = 0L
        for (t in turns) {
            val ov = overlap(w, t)
            if (ov > bestOverlap || (ov == bestOverlap && ov > 0 && t.speaker < best)) {
                bestOverlap = ov
                best = t.speaker
            }
        }
        if (best >= 0) return best
        // пересечения нет -- ближайший по расстоянию от середины слова до turn'а
        val mid2 = w.startMs + w.endMs // удвоенная середина (без дробей)
        var nearest = turns[0].speaker
        var nearestDist = Long.MAX_VALUE
        for (t in turns) {
            val d = distance2(mid2, t)
            if (d < nearestDist || (d == nearestDist && t.speaker < nearest)) {
                nearestDist = d
                nearest = t.speaker
            }
        }
        return nearest
    }

    private fun overlap(w: Word, t: SpeakerTurn): Long =
        maxOf(0L, minOf(w.endMs, t.endMs) - maxOf(w.startMs, t.startMs))

    /** Удвоенное расстояние от точки mid2/2 до отрезка turn'а. */
    private fun distance2(mid2: Long, t: SpeakerTurn): Long = when {
        mid2 < 2 * t.startMs -> 2 * t.startMs - mid2
        mid2 > 2 * t.endMs -> mid2 - 2 * t.endMs
        else -> 0L
    }

    // --- шаг 2 ---
    private fun smooth(ws: List<Word>, turns: List<SpeakerTurn>, speakers: IntArray) {
        var changed = true
        var guard = 0
        while (changed && guard++ < ws.size + 1) {
            changed = false
            val runs = runs(speakers)
            for (i in 1 until runs.size - 1) {
                val (from, to) = runs[i] // [from, to) индексы слов
                val prevSpk = speakers[runs[i - 1].first]
                val nextSpk = speakers[runs[i + 1].first]
                if (prevSpk != nextSpk) continue
                if (to - from > config.minIslandWords) continue
                val dur = ws[to - 1].endMs - ws[from].startMs
                if (dur >= config.minIslandMs) continue
                val spk = speakers[from]
                val weak = (from until to).all { ownOverlapShare(ws[it], turns, spk) < 0.5 }
                if (!weak) continue
                for (k in from until to) speakers[k] = prevSpk
                changed = true
                break // серии изменились -- пересчитать
            }
        }
    }

    private fun runs(speakers: IntArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (i in 1..speakers.size) {
            if (i == speakers.size || speakers[i] != speakers[start]) {
                out += start to i
                start = i
            }
        }
        return out
    }

    /** Доля слова, перекрытая лучшим turn'ом данного спикера (0, если слово нулевой длины/нет turn'ов). */
    private fun ownOverlapShare(w: Word, turns: List<SpeakerTurn>, speaker: Int): Double {
        val len = w.endMs - w.startMs
        if (len <= 0) return 0.0
        val best = turns.filter { it.speaker == speaker }.maxOfOrNull { overlap(w, it) } ?: 0L
        return best.toDouble() / len
    }

    // --- шаги 3, 4, 6 ---
    private fun group(ws: List<Word>, speakers: IntArray): List<Pair<Int, List<Word>>> {
        val out = ArrayList<Pair<Int, List<Word>>>()
        var cur = ArrayList<Word>()
        var curSpk = speakers[0]
        for (i in ws.indices) {
            val w = ws[i]
            if (cur.isNotEmpty()) {
                val gap = w.startMs - cur.last().endMs
                if (speakers[i] != curSpk || gap > config.maxGapMs) {
                    out += curSpk to cur
                    cur = ArrayList()
                }
            }
            curSpk = speakers[i]
            cur += w
        }
        if (cur.isNotEmpty()) out += curSpk to cur
        return out
    }

    // --- шаг 5 ---
    private fun renumber(groups: List<Pair<Int, List<Word>>>): List<Utterance> {
        val raw = groups.map { (spk, words) ->
            Triple(spk, words, words.first().startMs to words.last().endMs)
        }.sortedWith(compareBy({ it.third.first }, { it.third.second }))
        val ids = LinkedHashMap<Int, Int>()
        return raw.map { (spk, words, span) ->
            val n = ids.getOrPut(spk) { ids.size + 1 }
            Utterance(
                speakerId = speakerLabel(n),
                startMs = span.first,
                endMs = span.second,
                text = words.joinToString(" ") { it.text },
                words = words,
            )
        }
    }
}
