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

    @Test
    fun `bpe tokens become words with absolute times`() {
        val tokens = listOf("▁при", "вет", "▁мир")
        val ts = floatArrayOf(0.0f, 0.2f, 0.6f)
        val w = TokenWords.build("привет мир", tokens, ts, chunkStartMs = 10_000, chunkEndMs = 11_000)
        assertEquals(listOf("привет", "мир"), w.map { it.text })
        assertEquals(10_000L, w[0].startMs); assertEquals(10_600L, w[0].endMs) // до начала следующего слова
        assertEquals(10_600L, w[1].startMs); assertEquals(11_000L, w[1].endMs) // до конца чанка
    }

    @Test
    fun `char level tokens with separate space tokens`() {
        val tokens = listOf("п", "р", "и", "▁", "м", "и", "р")
        val ts = floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        val w = TokenWords.build("при мир", tokens, ts, 0, 1000)
        assertEquals(listOf("при", "мир"), w.map { it.text })
        assertEquals(400L, w[1].startMs)
    }

    @Test
    fun `word duration is capped and times stay in chunk`() {
        val w = TokenWords.build("а б", listOf("▁а", "▁б"), floatArrayOf(0f, 9f), 0, 20_000, maxWordMs = 1500)
        assertEquals(1500L, w[0].endMs)
        assertTrue(w.all { it.startMs in 0..20_000 && it.endMs in it.startMs..20_000 })
    }

    @Test
    fun `no timestamps spreads words proportionally`() {
        val w = TokenWords.build("раз три", listOf("a"), floatArrayOf(), 1000, 2000)
        assertEquals(listOf("раз", "три"), w.map { it.text })
        assertEquals(1000L, w.first().startMs)
        assertTrue(w.last().endMs <= 2001)
        assertTrue(w.zipWithNext().all { (a, b) -> a.endMs <= b.startMs + 1 })
        assertTrue(TokenWords.build("  ", emptyList(), floatArrayOf(), 0, 100).isEmpty())
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
}
