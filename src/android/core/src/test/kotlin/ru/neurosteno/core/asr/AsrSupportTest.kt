package ru.neurosteno.core.asr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.neurosteno.core.TimeSpan

class AsrSupportTest {
    private fun silenceWithQuietAt(n: Int, quietMs: Long): FloatArray =
        FloatArray(n * 16) { 0.5f }.also { x -> // "речь" везде, кроме тихого окна вокруг quietMs
            for (i in ((quietMs - 30) * 16).toInt() until ((quietMs + 30) * 16).toInt()) x[i] = 0.001f
        }

    @Test
    fun `chunks merge intervals while they fit and cut only between them`() {
        val x = FloatArray(60_000 * 16) { 0.1f }
        val speech = listOf(TimeSpan(0, 3000), TimeSpan(3500, 8000), TimeSpan(20_000, 24_000), TimeSpan(24_500, 46_000))
        // 0..24000 склеены (<= 25 с, пауза внутри чанка); добавление 24500..46000 вышло бы за 25 с -> рез в паузе 24000..24500
        assertEquals(listOf(TimeSpan(0, 24_000), TimeSpan(24_500, 46_000)), ChunkPlanner.plan(x, speech, 25_000))
        assertEquals(listOf(TimeSpan(0, 24_000)), ChunkPlanner.plan(x, listOf(TimeSpan(0, 3000), TimeSpan(20_000, 24_000)), 25_000))
    }

    @Test
    fun `long interval is cut at the quietest 20 ms frame in the second half of the window (python parity)`() {
        val x = silenceWithQuietAt(60_000, 18_000)
        val r = ChunkPlanner.plan(x, listOf(TimeSpan(0, 40_000)), 25_000)
        // окно [12500, 25000]: самый тихий кадр -- в 17970..18030, центр первого такого кадра (ранний побеждает)
        assertEquals(2, r.size)
        assertEquals(0L, r[0].startMs); assertEquals(r[0].endMs, r[1].startMs); assertEquals(40_000L, r[1].endMs)
        assertTrue(r[0].endMs in 17_970..18_030, "cut=${r[0].endMs}")
    }

    @Test
    fun `no quiet frame means middle of the window, ties go to the earliest frame`() {
        val flat = FloatArray(60_000 * 16) { 0.3f }
        assertEquals(12_510L, ChunkPlanner.quietestCut(flat, 12_500, 25_000)) // все кадры равны -> первый (центр 12500+10)
        assertEquals(12L, ChunkPlanner.quietestCut(flat, 10, 15)) // окно короче кадра -> середина окна
    }

    @Test
    fun `empty and invalid spans`() {
        val x = FloatArray(16_000)
        assertTrue(ChunkPlanner.plan(x, emptyList(), 25_000).isEmpty())
        assertTrue(ChunkPlanner.plan(x, listOf(TimeSpan(5, 5), TimeSpan(9, 3)), 25_000).isEmpty())
    }

    @Test
    fun `real tts file chunks equal the python reference (skipped without dataset)`() {
        val f = findCatsFile() ?: return
        val audio = ru.neurosteno.core.audio.WavDecoder.decode(f)
        // refpipe (medium.json): speech=[498..58580] -> chunks [498,23348] [23348,43198] [43198,58580]
        val r = ChunkPlanner.plan(audio.samples, listOf(TimeSpan(498, 58_580)), 25_000)
        assertEquals(listOf(TimeSpan(498, 23_348), TimeSpan(23_348, 43_198), TimeSpan(43_198, 58_580)), r)
    }

    private fun findCatsFile(): java.io.File? {
        var d: java.io.File? = java.io.File("").absoluteFile
        while (d != null) {
            val f = java.io.File(d, ".cache/data/ru-dialog-tts-cats/audio/cats_c01_v0_meeting_clean.wav")
            if (f.isFile) return f
            d = d.parentFile
        }
        return null
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

    @Test
    fun `punctuation token does not move the word end for punct models`() {
        val toks = listOf("▁Привет", ",", "▁мир", ".")
        val ts = listOf(0.0f, 0.5f, 0.8f, 1.2f)
        val w = TokenWords.build(toks, ts.toFloatArray(), 0, 5000, null, 40, 0, true)
        assertEquals(listOf("Привет,", "мир."), w.map { it.text })
        assertEquals(40L, w[0].endMs) // а не 500+40: запятая стоит на 0.5 с
        assertEquals(800L, w[1].startMs); assertEquals(840L, w[1].endMs)
    }

    @Test
    fun `start offset shifts word starts and ends`() {
        val w = TokenWords.build(listOf("▁да"), floatArrayOf(0.1f), 1000, 5000, null, 40, -30, false)
        assertEquals(1070L, w.single().startMs)
        assertEquals(1110L, w.single().endMs)
    }
}
