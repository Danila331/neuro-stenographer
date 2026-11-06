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
 * Сборка слов из токенов ASR. Токены BPE/sentencepiece начинают слово с «▁» (U+2581) либо с пробела;
 * символьные модели отдают пробел отдельным токеном. Времена токенов (секунды от начала чанка) переводятся в
 * абсолютные миллисекунды. Конец слова = начало следующего слова (но не дальше начала + [maxWordMs]) либо конец чанка.
 * Нет времён токенов (Whisper) -- слова равномерно распределяются по чанку пропорционально длине.
 */
object TokenWords {
    private const val MARKER = '▁'

    fun build(
        text: String,
        tokens: List<String>,
        timestampsSec: FloatArray,
        chunkStartMs: Long,
        chunkEndMs: Long,
        maxWordMs: Long = 2_000,
    ): List<Word> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val usable = tokens.isNotEmpty() && timestampsSec.size == tokens.size
        if (!usable) return spread(clean, chunkStartMs, chunkEndMs)

        // группируем токены в слова
        class Acc(val sb: StringBuilder, val startMs: Long)
        val groups = ArrayList<Acc>()
        var pendingSpace = true
        for (i in tokens.indices) {
            val raw = tokens[i]
            val tMs = chunkStartMs + (timestampsSec[i] * 1000f).roundToLong()
            val piece = raw.replace(MARKER, ' ').trim()
            if (piece.isEmpty()) { // токен-пробел
                pendingSpace = true
                continue
            }
            if (raw.startsWith(MARKER) || raw.startsWith(" ") || pendingSpace || groups.isEmpty()) {
                groups += Acc(StringBuilder(piece), tMs)
            } else {
                groups.last().sb.append(piece)
            }
            pendingSpace = raw.endsWith(MARKER) || raw.endsWith(" ")
        }
        val words = groups.filter { it.sb.isNotBlank() }
        if (words.isEmpty()) return spread(clean, chunkStartMs, chunkEndMs)

        return words.mapIndexed { i, g ->
            val start = g.startMs.coerceIn(chunkStartMs, chunkEndMs)
            val nextStart = if (i + 1 < words.size) words[i + 1].startMs else chunkEndMs
            var end = min(nextStart, start + maxWordMs).coerceIn(start, chunkEndMs)
            if (end <= start) end = min(chunkEndMs, start + 1)
            Word(g.sb.toString(), start, max(end, start))
        }
    }

    /** Равномерная раскладка слов по [startMs, endMs] пропорционально числу букв. */
    fun spread(text: String, startMs: Long, endMs: Long): List<Word> {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val total = parts.sumOf { max(1, it.length) }.toDouble()
        val span = max(1L, endMs - startMs).toDouble()
        var acc = 0.0
        return parts.map { w ->
            val s = startMs + (span * acc / total).roundToLong()
            acc += max(1, w.length)
            val e = startMs + (span * acc / total).roundToLong()
            Word(w, s, max(e, s + 1).coerceAtMost(max(endMs, s + 1)))
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
