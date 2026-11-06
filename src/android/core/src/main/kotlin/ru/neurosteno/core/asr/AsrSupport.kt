package ru.neurosteno.core.asr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.Word

/**
 * Разбиение речевых интервалов VAD на чанки для ASR (границы чанков -- паузы VAD).
 * Соседние интервалы склеиваются, пока пауза <= [mergeGapMs] и длина чанка <= [maxMs]; интервал длиннее [maxMs]
 * режется на равные части. Слишком короткие чанки (< [minMs]) расширяются симметрично до [minMs] (в пределах [0, durationMs]).
 */
object ChunkPlanner {
    fun plan(
        speech: List<TimeSpan>,
        durationMs: Long,
        maxMs: Long = 25_000,
        minMs: Long = 1_000,
        mergeGapMs: Long = 1_000,
        overlapMs: Long = 0,
    ): List<TimeSpan> {
        require(maxMs > 0)
        val sorted = speech.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()

        val merged = ArrayList<TimeSpan>()
        var cur = sorted.first()
        for (s in sorted.drop(1)) {
            val gap = s.startMs - cur.endMs
            if (gap <= mergeGapMs && max(cur.endMs, s.endMs) - cur.startMs <= maxMs) {
                cur = TimeSpan(cur.startMs, max(cur.endMs, s.endMs))
            } else {
                merged += cur
                cur = s
            }
        }
        merged += cur

        val split = ArrayList<TimeSpan>()
        for (m in merged) {
            val len = m.endMs - m.startMs
            if (len <= maxMs) {
                split += m
            } else {
                val parts = ((len + maxMs - 1) / maxMs).toInt()
                val step = len.toDouble() / parts
                for (i in 0 until parts) {
                    val s = m.startMs + (step * i).roundToLong()
                    val e = if (i == parts - 1) m.endMs else m.startMs + (step * (i + 1)).roundToLong()
                    split += TimeSpan(max(m.startMs, s - if (i > 0) overlapMs else 0), min(m.endMs, e))
                }
            }
        }

        return split.map { c ->
            val len = c.endMs - c.startMs
            if (len >= minMs || durationMs <= 0) {
                c
            } else {
                val grow = (minMs - len + 1) / 2
                var s = max(0L, c.startMs - grow)
                var e = min(durationMs, c.endMs + grow)
                if (e - s < minMs) { // упёрлись в край записи -- добираем с другой стороны
                    if (s == 0L) e = min(durationMs, s + minMs) else s = max(0L, e - minMs)
                }
                TimeSpan(s, e)
            }
        }
    }
}

/**
 * Сборка слов из токенов ASR -- порт `build_words`/`spread_words` эталона (`src/eval/refpipe/refpipe/asr.py`).
 *
 * sherpa-onnx отдаёт на токен только ВРЕМЯ НАЧАЛА (секунды от начала чанка). Токены: символы или BPE-куски,
 * `▁`/пробел -- граница слова. `start` слова = время первого токена (+ [startOffsetMs]); `end` = время последнего
 * токена + [tailMs] (для TDT-моделей вместо хвоста берётся реальная длительность токена), но не позже начала
 * следующего слова и не позже конца чанка. Абсолютное время = начало чанка + round(ts * 1000).
 *
 * [keepPunct] = false ("none"): слово приводится к нижнему регистру, остаются буквы/цифры и внутренние `-`/`'`;
 * true ("asr_native"): знаки сохраняются, одиночная пунктуация приклеивается к предыдущему слову.
 */
object TokenWords {
    private const val MARKER = '\u2581'
    private const val PUNCT_CHARS = ".,!?;:\u2026\u2014\u2013-\u00ab\u00bb\"'()[]"

    fun plainWord(t: String): String =
        t.filter { it.isLetterOrDigit() || it == '-' || it == '\'' }.trim('-', '\'').lowercase()

    private class Acc(var text: StringBuilder, val start: Long, var last: Long, var lastDur: Long?)

    fun build(
        tokens: List<String>,
        timestampsSec: FloatArray,
        chunkStartMs: Long,
        chunkEndMs: Long,
        durationsSec: FloatArray? = null,
        tailMs: Long = 40,
        startOffsetMs: Long = 0,
        keepPunct: Boolean = false,
    ): List<Word> {
        val n = minOf(tokens.size, timestampsSec.size)
        val words = ArrayList<Acc>()
        var cur: Acc? = null
        fun flush() {
            cur?.let { if (it.text.isNotBlank()) words += it }
            cur = null
        }
        for (i in 0 until n) {
            val tk = tokens[i].replace(MARKER, ' ')
            if (tk.length > 2 && tk.startsWith("<") && tk.endsWith(">")) continue // спец-токены
            val lead = tk.isNotEmpty() && tk[0].isWhitespace()
            val body = tk.trim()
            if (lead) flush()
            if (body.isNotEmpty()) {
                val tMs = chunkStartMs + (timestampsSec[i].toDouble() * 1000 + 0.5).toLong() + startOffsetMs
                val c = cur ?: Acc(StringBuilder(), tMs, tMs, null).also { cur = it }
                c.text.append(body)
                c.last = tMs
                c.lastDur = if (durationsSec != null && i < durationsSec.size) (durationsSec[i].toDouble() * 1000 + 0.5).toLong() else null
            }
            if (tk.isNotEmpty() && tk.last().isWhitespace() && tk.length > 1) flush()
        }
        flush()

        val out = ArrayList<Word>()
        for ((k, w) in words.withIndex()) {
            val text = if (keepPunct) w.text.toString() else plainWord(w.text.toString())
            if (text.isEmpty()) continue
            if (keepPunct && out.isNotEmpty() && text.all { it in PUNCT_CHARS }) {
                val p = out.removeAt(out.size - 1)
                out += Word(p.text + text, p.startMs, p.endMs, p.confidence)
                continue
            }
            val start = maxOf(chunkStartMs, w.start)
            var end = w.last + (w.lastDur?.takeIf { it != 0L } ?: tailMs)
            if (k + 1 < words.size) end = minOf(end, maxOf(words[k + 1].start, start + 1))
            end = minOf(maxOf(end, start + 1), maxOf(chunkEndMs, start + 1))
            out += Word(text, start, end, null)
        }
        return out
    }

    /** Нет времён токенов (Whisper): слова равномерно по чанку пропорционально длине (+1 на пробел). */
    fun spread(text: String, chunkStartMs: Long, chunkEndMs: Long, keepPunct: Boolean = false): List<Word> {
        val ws = text.split(Regex("\\s+")).map { if (keepPunct) it else plainWord(it) }.filter { it.isNotEmpty() }
        if (ws.isEmpty()) return emptyList()
        val total = ws.sumOf { it.length + 1 }.toLong()
        val dur = chunkEndMs - chunkStartMs
        var acc = 0L
        return ws.map { w ->
            val a = chunkStartMs + dur * acc / total
            acc += w.length + 1
            val b = chunkStartMs + dur * acc / total
            Word(w, a, maxOf(b - 1, a + 1))
        }
    }
}

/**
 * Раскладка файлов в каталоге модели sherpa-onnx (по именам -- чистая функция, тестируется на JVM).
 * Предпочитает int8-варианты, если лежат оба.
 */
data class AsrModelFiles(
    val kind: Kind,
    val model: String? = null,
    val encoder: String? = null,
    val decoder: String? = null,
    val joiner: String? = null,
    val tokens: String,
) {
    enum class Kind { NEMO_CTC, NEMO_TRANSDUCER, TRANSDUCER, WHISPER, PARAFORMER }

    companion object {
        /** [engine] -- поле `asr.engine` конфига; [files] -- имена файлов (не пути) в каталоге модели. */
        fun resolve(engine: String, files: List<String>): AsrModelFiles {
            fun pick(vararg pred: (String) -> Boolean): String? {
                for (p in pred) files.filter(p).sorted().let { if (it.isNotEmpty()) return it.first() }
                return null
            }
            val onnx = { n: String -> n.endsWith(".onnx") }
            val int8 = { n: String -> n.endsWith(".int8.onnx") }
            fun withWord(w: String, int8Only: Boolean) = { n: String -> n.contains(w) && n.endsWith(".onnx") && (!int8Only || n.contains("int8")) }

            val tokens = pick({ it == "tokens.txt" }, { it.endsWith("tokens.txt") })
                ?: throw IllegalArgumentException("В каталоге модели нет tokens.txt")
            val e = engine.lowercase()
            return when {
                e.contains("whisper") -> {
                    val enc = pick(withWord("encoder", true), withWord("encoder", false))
                    val dec = pick(withWord("decoder", true), withWord("decoder", false))
                    require(enc != null && dec != null) { "Whisper: нужны *-encoder*.onnx и *-decoder*.onnx" }
                    AsrModelFiles(Kind.WHISPER, encoder = enc, decoder = dec, tokens = pick({ it.endsWith("-tokens.txt") }, { it == "tokens.txt" })!!)
                }
                e.contains("ctc") -> {
                    val m = pick({ it == "model.int8.onnx" }, int8, { it == "model.onnx" }, onnx)
                        ?: throw IllegalArgumentException("CTC: нет model*.onnx")
                    AsrModelFiles(Kind.NEMO_CTC, model = m, tokens = tokens)
                }
                e.contains("paraformer") -> {
                    val m = pick({ it == "model.int8.onnx" }, int8, { it == "model.onnx" }, onnx)
                        ?: throw IllegalArgumentException("Paraformer: нет model*.onnx")
                    AsrModelFiles(Kind.PARAFORMER, model = m, tokens = tokens)
                }
                else -> { // rnnt / transducer
                    val enc = pick(withWord("encoder", true), withWord("encoder", false))
                    val dec = pick(withWord("decoder", true), withWord("decoder", false))
                    val join = pick(withWord("joiner", true), withWord("joiner", false))
                    require(enc != null && dec != null && join != null) { "Transducer: нужны encoder/decoder/joiner .onnx" }
                    val kind = if (e.contains("gigaam") || e.contains("nemo")) Kind.NEMO_TRANSDUCER else Kind.TRANSDUCER
                    AsrModelFiles(kind, encoder = enc, decoder = dec, joiner = join, tokens = tokens)
                }
            }
        }
    }
}

/** Операции над речевыми интервалами VAD. */
object SpanOps {
    /** Расширяет каждый интервал на [padMs] с обеих сторон (в пределах [0, durationMs]) и склеивает пересёкшиеся. */
    fun padAndMerge(spans: List<TimeSpan>, padMs: Long, durationMs: Long): List<TimeSpan> {
        val padded = spans.filter { it.endMs > it.startMs }
            .map { TimeSpan(max(0L, it.startMs - padMs), min(durationMs, it.endMs + padMs)) }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        val out = ArrayList<TimeSpan>()
        for (s in padded) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) out[out.size - 1] = TimeSpan(last.startMs, max(last.endMs, s.endMs))
            else out += s
        }
        return out
    }

    /** Суммарная длительность. */
    fun totalMs(spans: List<TimeSpan>): Long = spans.sumOf { it.endMs - it.startMs }
}
