package ru.neurosteno.core.asr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.neurosteno.core.TimeSpan

class AsrSupportTest {
    @Test
    fun `chunks merge close spans and respect max length`() {
        val speech = listOf(TimeSpan(0, 3000), TimeSpan(3500, 8000), TimeSpan(20_000, 24_000), TimeSpan(24_500, 46_000))
        // 0..8000 склеены (пауза 500); 20_000..46_000 = 26 с > 25 -> не склеиваются
        val r = ChunkPlanner.plan(speech, 60_000, maxMs = 25_000)
        assertEquals(listOf(TimeSpan(0, 8000), TimeSpan(20_000, 24_000), TimeSpan(24_500, 46_000)), r)
        // большая пауза (> mergeGap) тоже разделяет
        assertEquals(2, ChunkPlanner.plan(listOf(TimeSpan(0, 2000), TimeSpan(4000, 6000)), 10_000, mergeGapMs = 1000).size)
    }

    @Test
    fun `long span is split evenly`() {
        val r = ChunkPlanner.plan(listOf(TimeSpan(0, 60_000)), 60_000, maxMs = 25_000)
        assertEquals(3, r.size)
        assertTrue(r.all { it.endMs - it.startMs <= 25_000 })
        assertEquals(0L, r.first().startMs); assertEquals(60_000L, r.last().endMs)
        assertEquals(r.zipWithNext().map { it.first.endMs }, r.zipWithNext().map { it.second.startMs })
    }

    @Test
    fun `short chunks are padded to min length within the file`() {
        val r = ChunkPlanner.plan(listOf(TimeSpan(5_000, 5_200), TimeSpan(30_000, 30_100)), 60_000, minMs = 1_000)
        assertEquals(listOf(1000L, 1000L), r.map { it.endMs - it.startMs })
        assertEquals(TimeSpan(0, 1000), ChunkPlanner.plan(listOf(TimeSpan(0, 100)), 10_000, minMs = 1_000).single())
        assertEquals(TimeSpan(9_000, 10_000), ChunkPlanner.plan(listOf(TimeSpan(9_950, 10_000)), 10_000, minMs = 1_000).single())
    }

    @Test
    fun `empty and invalid spans`() {
        assertTrue(ChunkPlanner.plan(emptyList(), 1000).isEmpty())
        assertTrue(ChunkPlanner.plan(listOf(TimeSpan(5, 5), TimeSpan(9, 3)), 1000).isEmpty())
    }

    private fun words(tokens: List<String>, ts: List<Float>, start: Long = 0, end: Long = 5000, tail: Long = 40, keep: Boolean = false, dur: List<Float>? = null) =
        TokenWords.build(tokens, ts.toFloatArray(), start, end, dur?.toFloatArray(), tail, 0, keep)

    @Test
    fun `char tokens to words with absolute time (python parity)`() {
        val w = words("да нет".map { it.toString() }, listOf(0.00f, 0.04f, 0.08f, 0.20f, 0.24f, 0.28f), 1000, 2000)
        assertEquals(listOf(Triple("да", 1000L, 1080L), Triple("нет", 1200L, 1320L)), w.map { Triple(it.text, it.startMs, it.endMs) })
    }

    @Test
    fun `end is clipped by next word start`() {
        val w = words("а б".map { it.toString() }, listOf(0.00f, 0.04f, 0.06f), 0, 1000, tail = 200)
        assertEquals(60L, w[0].endMs)
        assertEquals(60L, w[1].startMs)
        assertEquals(260L, w[1].endMs)
    }

    @Test
    fun `bpe pieces with underscore boundary`() {
        val w = words(listOf("▁при", "вет", "▁мир"), listOf(0.0f, 0.2f, 0.6f))
        assertEquals(listOf("привет" to 0L, "мир" to 600L), w.map { it.text to it.startMs })
    }

    @Test
    fun `plain mode strips punctuation, native keeps it, lone punctuation attaches`() {
        val toks = "Привет, мир!".map { it.toString() }
        val ts = toks.indices.map { it * 0.04f }
        assertEquals(listOf("привет", "мир"), words(toks, ts).map { it.text })
        assertEquals(listOf("Привет,", "мир!"), words(toks, ts, keep = true).map { it.text })
        val lone = words(listOf("▁да", "▁", ",", "▁нет"), listOf(0.0f, 0.1f, 0.1f, 0.5f), keep = true)
        assertEquals(listOf("да,", "нет"), lone.map { it.text })
    }

    @Test
    fun `special tokens empty input and tdt durations`() {
        assertTrue(words(listOf("<unk>"), listOf(0f), 0, 10).isEmpty())
        assertTrue(words(emptyList(), emptyList(), 0, 10).isEmpty())
        val w = words(listOf("▁а"), listOf(0.0f), 0, 5000, dur = listOf(0.5f))
        assertEquals(500L, w[0].endMs) // реальная длительность токена вместо хвоста 40 мс
    }

    @Test
    fun `word times stay inside the chunk and never go backwards`() {
        val w = words(listOf("▁а", "▁б"), listOf(0f, 9f), 0, 10_000, tail = 1500)
        assertTrue(w.all { it.startMs >= 0 && it.endMs in (it.startMs + 1)..10_000 })
        assertTrue(w.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test
    fun `spread covers chunk proportionally`() {
        val w = TokenWords.spread("раз три", 1000, 2000)
        assertEquals(1000L, w.first().startMs)
        assertTrue(w.last().endMs <= 2000)
        assertTrue(w[0].endMs <= w[1].startMs)
        assertTrue(TokenWords.spread("  ", 0, 100).isEmpty())
    }

    @Test
    fun `plain word`() {
        assertEquals("привет", TokenWords.plainWord("Привет,"))
        assertEquals("кто-то", TokenWords.plainWord("-Кто-то!"))
        assertEquals("", TokenWords.plainWord("..."))
    }

    @Test
    fun `model layout nemo ctc prefers int8`() {
        val f = AsrModelFiles.resolve("gigaam_ctc", listOf("model.onnx", "model.int8.onnx", "tokens.txt", "README.md"))
        assertEquals(AsrModelFiles.Kind.NEMO_CTC, f.kind)
        assertEquals("model.int8.onnx", f.model)
        assertEquals("tokens.txt", f.tokens)
    }

    @Test
    fun `model layout gigaam rnnt and whisper`() {
        val r = AsrModelFiles.resolve("gigaam_rnnt", listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
        assertEquals(AsrModelFiles.Kind.NEMO_TRANSDUCER, r.kind)
        assertEquals(listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx"), listOf(r.encoder, r.decoder, r.joiner))
        val z = AsrModelFiles.resolve("zipformer", listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"))
        assertEquals(AsrModelFiles.Kind.TRANSDUCER, z.kind)
        val w = AsrModelFiles.resolve("whisper", listOf("large-v3-turbo-encoder.int8.onnx", "large-v3-turbo-decoder.int8.onnx", "large-v3-turbo-tokens.txt"))
        assertEquals(AsrModelFiles.Kind.WHISPER, w.kind)
        assertEquals("large-v3-turbo-tokens.txt", w.tokens)
    }

    @Test
    fun `model layout errors are readable`() {
        assertThrows<IllegalArgumentException> { AsrModelFiles.resolve("gigaam_ctc", listOf("model.onnx")) }
        assertThrows<IllegalArgumentException> { AsrModelFiles.resolve("gigaam_rnnt", listOf("encoder.onnx", "tokens.txt")) }
    }

    @Test
    fun `pad and merge`() {
        val r = SpanOps.padAndMerge(listOf(TimeSpan(1000, 2000), TimeSpan(2200, 3000), TimeSpan(9900, 10_000)), 150, 10_000)
        assertEquals(listOf(TimeSpan(850, 3150), TimeSpan(9750, 10_000)), r)
        assertEquals(listOf(TimeSpan(0, 250)), SpanOps.padAndMerge(listOf(TimeSpan(0, 100)), 150, 10_000))
        assertEquals(3150L + 0, SpanOps.totalMs(listOf(TimeSpan(0, 3150))))
    }
}
