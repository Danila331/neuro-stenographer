package ru.neurosteno.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TierConfigTest {
    private fun load(tier: String) = File(TestPaths.repoRoot, "config/tiers/$tier.json").readText()

    @Test
    fun `all real tier configs parse`() {
        for (tier in listOf("lite", "medium", "hard")) {
            val c = TierConfig.parse(load(tier))
            assertEquals(tier, c.tier)
            assertTrue(c.numThreads > 0)
            assertEquals(16_000, c.audio.sampleRate)
            assertTrue(c.vad.model.endsWith(".onnx"))
            assertTrue(c.asr.modelDir.isNotBlank())
            assertEquals(1500L, c.align.maxGapMs)
            assertTrue(c.requiredModelPaths().containsAll(listOf(c.vad.model, c.asr.modelDir, c.diarization.segmentation)))
        }
    }

    @Test
    fun `tier specific values are read`() {
        val lite = TierConfig.parse(load("lite"))
        assertTrue(lite.asr.engine.startsWith("gigaam"))
        assertTrue(lite.asr.decoding.isNotBlank())
        assertNull(lite.asr.secondPass)
        assertFalse(lite.audio.denoise.enabled)
        val hard = TierConfig.parse(load("hard"))
        assertTrue(hard.asr.decoding.isNotBlank())
        val full = TierConfig.parse("""{"audio":{"denoise":{"enabled":true,"model":"denoise/gtcrn_simple.onnx"}},
            "asr":{"second_pass":{"engine":"whisper","model_dir":"asr/whisper-large"}},"llm":{"gpu_layers":99}}""")
        assertNotNull(full.asr.secondPass)
        assertEquals("whisper", full.asr.secondPass!!.engine)
        assertTrue(full.requiredModelPaths().contains("denoise/gtcrn_simple.onnx"))
        assertTrue(full.requiredModelPaths().contains("asr/whisper-large"))
        assertEquals(99, full.llm.gpuLayers)
        assertNull(hard.diarization.clustering.numSpeakers)
    }

    @Test
    fun `unknown fields are ignored and missing take defaults`() {
        val c = TierConfig.parse("""{"tier":"x","brand_new":{"a":1},"vad":{"threshold":0.7,"future":true}}""")
        assertEquals("x", c.tier)
        assertEquals(0.7, c.vad.threshold)
        assertEquals(300, c.vad.minSilenceMs) // дефолт
        assertEquals(AlignConfig(), c.align)
        assertEquals(4, c.numThreads)
    }

    @Test
    fun `nulls and wrong types for optional fields do not crash`() {
        val c = TierConfig.parse("""{"asr":{"second_pass":null,"hotwords_file":null},"llm":{"model":null}}""")
        assertNull(c.asr.secondPass)
        assertNull(c.llm.model)
    }

    @Test
    fun `broken json gives readable error`() {
        val e = assertThrows<IllegalArgumentException> { TierConfig.parse("{not json") }
        assertTrue(e.message!!.contains("конфиг"))
    }

    @Test
    fun `hash is stable and content dependent`() {
        assertEquals(TierConfig.hash("a"), TierConfig.hash("a"))
        assertNotEquals(TierConfig.hash("a"), TierConfig.hash("b"))
        assertEquals(12, TierConfig.hash("a").length)
    }

    @Test
    fun `new reference fields are parsed with defaults`() {
        val c = TierConfig.parse(load("medium"))
        assertEquals(40, c.asr.wordTailMs)
        assertTrue(c.asr.wordStartOffsetMs <= 0)
        assertEquals(1, c.asr.batchSize)
        assertEquals("ru", c.asr.language)
        assertTrue(c.diarization.enabled)
        assertTrue(c.diarization.windowShiftRatio in 0.05..1.0)
        assertNull(c.diarization.numThreads)
        val d = TierConfig.parse("""{"asr":{},"diarization":{}}""")
        assertEquals(40, d.asr.wordTailMs)
        assertTrue(d.diarization.enabled)
        assertFalse(TierConfig.parse("""{"diarization":{"enabled":false}}""").diarization.enabled)
    }

    @Test
    fun `essential models are a subset of all required models`() {
        for (tier in listOf("lite", "medium", "hard")) {
            val c = TierConfig.parse(load(tier))
            assertEquals(4, c.essentialModelPaths().size)
            assertTrue(c.requiredModelPaths().containsAll(c.essentialModelPaths()))
        }
    }

    @Test
    fun `normalize_gain is parsed, legacy key works and defaults to off`() {
        assertFalse(TierConfig.parse("{}").audio.normalizeGain.active)
        val c = TierConfig.parse("""{"audio":{"normalize_gain":{"enabled":true,"mode":"peak","peak_dbfs":-3.0,"max_gain_db":30,"skip_if_peak_below_dbfs":-80}}}""")
        val g = c.audio.normalizeGain
        assertTrue(g.active)
        assertEquals(-3.0, g.peakDbfs); assertEquals(30.0, g.maxGainDb); assertEquals(-80.0, g.skipIfPeakBelowDbfs)
        assertEquals(g, PipelineSettings.from(c, "1").normalizeGain)
        val legacy = TierConfig.parse("""{"audio":{"normalize_gain":{"enabled":true,"mode":"peak","target_peak_dbfs":-2.0}}}""")
        assertEquals(-2.0, legacy.audio.normalizeGain.peakDbfs)
        TierConfig.parse(load("medium")) // реальный конфиг со всеми новыми ключами парсится
    }

    @Test
    fun `b30 fields have defaults and are parsed`() {
        val d = TierConfig.parse("{}")
        assertFalse(d.language.perChunk)
        assertEquals("as_is", d.postprocess.numbers)
        assertFalse(d.postprocess.dropTinyChunks.enabled)
        assertEquals(2, d.postprocess.dropTinyChunks.maxChars)
        val c = TierConfig.parse("""{"language":{"mode":"auto","per_chunk":true},"postprocess":{"numbers":"words","drop_tiny_chunks":{"enabled":true,"max_chars":3}}}""")
        assertTrue(c.language.perChunk); assertTrue(c.postprocess.spellNumbers)
        assertTrue(c.postprocess.dropTinyChunks.enabled); assertEquals(3, c.postprocess.dropTinyChunks.maxChars)
        val real = TierConfig.parse(load("medium"))
        assertTrue(real.asr.wordStartOffsetMs <= 0)
    }

    @Test
    fun `relabel config has defaults and is read from tier files`() {
        val d = TierConfig.parse("{}").diarization.relabel
        assertFalse(d.enabled); assertEquals(300, d.gapMs); assertEquals(6, d.kMax); assertNull(d.k1SilhouetteThr)
        val r = TierConfig.parse(load("medium")).diarization.relabel
        assertTrue(r.gapMs > 0 && r.kMin >= 2 && r.embedMaxS > 0)
        val c = TierConfig.parse("""{"diarization":{"relabel":{"enabled":true,"gap_ms":200,"k1_silhouette_thr":0.1}}}""").diarization.relabel
        assertTrue(c.enabled); assertEquals(200, c.gapMs); assertEquals(0.1, c.k1SilhouetteThr)
    }

    @Test
    fun `pipeline settings from config`() {
        val s = PipelineSettings.from(TierConfig.parse(load("medium")), "1.0")
        assertEquals("medium", s.tier)
        assertEquals(1500L, s.align.maxGapMs)
        assertTrue(s.models.containsKey("asr"))
    }

    @Test
    fun `language section and asr_en are optional - missing means ru only`() {
        val real = TierConfig.parse(load("medium"))
        val expected = if (real.asrEn != null) setOf("ru", "en") else setOf("ru")
        assertEquals(expected, PipelineSettings.from(real, "1").supportedLanguages)
        val bare = TierConfig.parse("""{"tier":"x"}""")
        assertEquals("ru", bare.language.effectiveMode)
        assertEquals(10, bare.language.lidSeconds)
        assertNull(bare.asrEn)
    }

    @Test
    fun `language auto with asr_en and lid model`() {
        val c = TierConfig.parse(
            """{"tier":"medium","language":{"mode":"AUTO","lid_model":"lid/model.onnx"},
               "asr_en":{"engine":"whisper","model_dir":"asr/whisper-small.en"}}""",
        )
        assertEquals("auto", c.language.effectiveMode)
        assertEquals("asr/whisper-small.en", c.asrEn!!.modelDir)
        assertTrue(c.requiredModelPaths().containsAll(listOf("lid/model.onnx", "asr/whisper-small.en")))
        val s = PipelineSettings.from(c, "1")
        assertEquals(setOf("ru", "en"), s.supportedLanguages)
        assertEquals("auto", s.languageMode)
        assertEquals("lid/model.onnx", s.models["lid"])
    }

    @Test
    fun `unknown language mode means ru and lid model is not required then`() {
        val c = TierConfig.parse("""{"language":{"mode":"klingon","lid_model":"lid/m.onnx"}}""")
        assertEquals("ru", c.language.effectiveMode)
        assertFalse(c.requiredModelPaths().contains("lid/m.onnx"))
    }
}
