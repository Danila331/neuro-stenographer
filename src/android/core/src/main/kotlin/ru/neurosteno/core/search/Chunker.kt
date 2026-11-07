package ru.neurosteno.core.search

import ru.neurosteno.core.summary.SUtt

/**
 * Нарезка расшифровки на окна для индекса (порт `bench_rag/chunker.py`). Режимы: `utt` -- реплика; `turn` -- склейка соседних реплик
 * одного говорящего (пауза <= 1.5 с, <= 400 символов); `win<W>[s<S>]` -- окна из W ходов с шагом S (по умолчанию W/2);
 * по умолчанию в конфиге -- `win3s1`.
 */
object Chunker {
    const val DEFAULT_MODE = "win3s1"

    private fun len(s: String) = s.codePointCount(0, s.length) // Python len() считает кодовые точки

    /** Склеивает подряд идущие реплики одного говорящего (конвейер режет предложения по числам и т. п.). */
    fun mergeTurns(utts: List<SUtt>, maxGapMs: Long = 1500, maxChars: Int = 400): List<List<SUtt>> {
        val groups = ArrayList<MutableList<SUtt>>()
        for (u in utts) {
            val last = groups.lastOrNull()
            if (last != null && last.last().speakerId == u.speakerId && u.startMs - last.last().endMs <= maxGapMs &&
                last.sumOf { len(it.text) + 1 } + len(u.text) <= maxChars
            ) last += u else groups += mutableListOf(u)
        }
        return groups
    }

    /** Окна записи [rec]; идентификаторы окон -- с [startId] подряд. */
    fun chunkRecord(rec: SearchRecord, mode: String = DEFAULT_MODE, startId: Long = 0): List<Chunk> {
        val base: List<List<SUtt>> = if (mode == "utt") rec.utts.map { listOf(it) } else mergeTurns(rec.utts)
        var units = base
        if (mode.startsWith("win")) {
            val body = mode.substring(3)
            val w = body.substringBefore('s').toInt()
            val stride = if ('s' in body) body.substringAfter('s').toInt() else maxOf(1, w / 2)
            val win = ArrayList<List<SUtt>>()
            var i = 0
            while (true) {
                val g = base.drop(i).take(w)
                if (g.isEmpty()) break
                win += g.flatten()
                if (i + w >= base.size) break
                i += stride
            }
            units = win
        }
        return units.mapIndexed { k, grp ->
            val text = grp.joinToString(" ") { it.text }
            val multi = grp.map { it.speakerId }.toSet().size > 1 || mode.startsWith("win")
            val emb = if (multi) grp.joinToString("\n") { "Говорящий ${it.spk}: ${it.text}" } else text
            Chunk(startId + k, rec.id, grp.map { it.idx }, grp.map { it.speakerId }, grp.first().startMs, grp.last().endMs, text, emb)
        }
    }
}
