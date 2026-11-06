package ru.neurosteno.core

/**
 * Выравнивание слов по говорящим (ARCHITECTURE §5). Чистая детерминированная функция, только целочисленная
 * арифметика; порт 1:1 эталона `src/eval/refpipe/refpipe/align.py` (кросс-проверка -- `AlignCasesTest` на тех же
 * `align_cases.json`). Детали, не закреплённые в §5:
 *  - слова стабильно сортируются по (start, end), пустые слова отбрасываются; turns -- по (start, end, speaker),
 *    этот порядок решает все ничьи;
 *  - шаг 1: побеждает turn со СТРОГО наибольшим пересечением (при равенстве -- первый в порядке turn'ов);
 *    без пересечений -- ближайший turn по расстоянию от середины слова `(start+end)/2` (внутри turn'а 0);
 *    нет turn'ов -- speaker 0; `own` = пересечение с выбранным turn'ом (0 для запасного «ближайшего»);
 *  - шаг 2: остров -- серия подряд идущих слов одного спикера между двумя сериями ОДНОГО спикера,
 *    `len <= minIslandWords`, `last.end - first.start < minIslandMs`, и у каждого слова `2*own < max(1, длина)`.
 *    Острова определяются один раз по исходным сериям (без итераций) и берут спикера соседей;
 *  - шаг 4: `end = max(last.end, start + 1)`; шаг 5: сортировка реплик по (start, end), перенумерация 1..N.
 */
class Aligner(private val config: AlignConfig = AlignConfig()) {

    fun align(words: List<Word>, turns: List<SpeakerTurn>): List<Utterance> {
        val ws = words
            .filter { it.text.isNotBlank() }
            .map { it.copy(text = it.text.trim()) }
            .sortedWith(compareBy<Word>({ it.startMs }, { it.endMs }))
        if (ws.isEmpty()) return emptyList()

        val sortedTurns = turns.sortedWith(compareBy<SpeakerTurn>({ it.startMs }, { it.endMs }, { it.speaker }))
        val speakers = IntArray(ws.size)
        val owns = LongArray(ws.size)
        for (i in ws.indices) assign(ws[i], sortedTurns, speakers, owns, i)
        val smoothed = smooth(ws, speakers, owns)
        return renumber(group(ws, smoothed))
    }

    // --- шаг 1 ---
    private fun assign(w: Word, turns: List<SpeakerTurn>, speakers: IntArray, owns: LongArray, i: Int) {
        if (turns.isEmpty()) {
            speakers[i] = 0
            owns[i] = 0
            return
        }
        var bestIdx = -1
        var bestOv = 0L
        for (k in turns.indices) {
            val ov = overlap(w, turns[k])
            if (ov > bestOv) {
                bestIdx = k
                bestOv = ov
            }
        }
        if (bestIdx < 0) {
            val mid = (w.startMs + w.endMs) / 2
            var bestD = Long.MAX_VALUE
            for (k in turns.indices) {
                val t = turns[k]
                val d = if (mid >= t.startMs && mid <= t.endMs) 0L else minOf(kotlin.math.abs(mid - t.startMs), kotlin.math.abs(mid - t.endMs))
                if (d < bestD) {
                    bestD = d
                    bestIdx = k
                }
            }
        }
        speakers[i] = turns[bestIdx].speaker
        owns[i] = bestOv
    }

    private fun overlap(w: Word, t: SpeakerTurn): Long =
        maxOf(0L, minOf(w.endMs, t.endMs) - maxOf(w.startMs, t.startMs))

    // --- шаг 2 ---
    private fun smooth(ws: List<Word>, speakers: IntArray, owns: LongArray): IntArray {
        val out = speakers.copyOf()
        val runs = ArrayList<IntArray>() // [from, to)
        var i = 0
        while (i < ws.size) {
            var j = i
            while (j < ws.size && speakers[j] == speakers[i]) j++
            runs += intArrayOf(i, j)
            i = j
        }
        for (k in 1 until runs.size - 1) {
            val a = runs[k][0]
            val b = runs[k][1]
            val prev = speakers[runs[k - 1][0]]
            val next = speakers[runs[k + 1][0]]
            if (prev != next) continue
            if (b - a > config.minIslandWords) continue
            if (ws[b - 1].endMs - ws[a].startMs >= config.minIslandMs) continue
            if ((a until b).any { 2 * owns[it] >= maxOf(1L, ws[it].endMs - ws[it].startMs) }) continue
            for (x in a until b) out[x] = prev
        }
        return out
    }

    // --- шаги 3, 4, 6 ---
    private fun group(ws: List<Word>, speakers: IntArray): List<Pair<Int, List<Word>>> {
        val out = ArrayList<Pair<Int, ArrayList<Word>>>()
        for (i in ws.indices) {
            val w = ws[i]
            val last = out.lastOrNull()
            if (last != null && last.first == speakers[i] && w.startMs - last.second.last().endMs <= config.maxGapMs) {
                last.second += w
            } else {
                out += speakers[i] to arrayListOf(w)
            }
        }
        return out
    }

    // --- шаг 5 ---
    private fun renumber(groups: List<Pair<Int, List<Word>>>): List<Utterance> {
        class Raw(val spk: Int, val start: Long, val end: Long, val words: List<Word>)
        val raw = groups.map { (spk, words) ->
            val start = words.first().startMs
            Raw(spk, start, maxOf(words.last().endMs, start + 1), words)
        }.sortedWith(compareBy({ it.start }, { it.end }))
        val ids = LinkedHashMap<Int, Int>()
        return raw.map { r ->
            val n = ids.getOrPut(r.spk) { ids.size + 1 }
            Utterance(
                speakerId = speakerLabel(n),
                startMs = r.start,
                endMs = r.end,
                text = r.words.joinToString(" ") { it.text },
                words = r.words,
            )
        }
    }
}
