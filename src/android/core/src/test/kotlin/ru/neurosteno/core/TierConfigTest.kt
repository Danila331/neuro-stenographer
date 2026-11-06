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
        assertEquals("gigaam_ctc", lite.asr.engine)
        assertEquals("greedy_search", lite.asr.decoding)
        assertNull(lite.asr.secondPass)
        assertFalse(lite.audio.denoise.enabled)
        val hard = TierConfig.parse(load("hard"))
        assertEquals("modified_beam_search", hard.asr.decoding)
        assertNotNull(hard.asr.secondPass)
        assertEquals("whisper", hard.asr.secondPass!!.engine)
        assertTrue(hard.audio.denoise.enabled)
        assertTrue(hard.requiredModelPaths().contains("denoise/gtcrn_simple.onnx"))
        assertTrue(hard.requiredModelPaths().contains(hard.asr.secondPass!!.modelDir))
        assertEquals(99, hard.llm.gpuLayers)
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
    fun `pipeline settings from config`() {
        val s = PipelineSettings.from(TierConfig.parse(load("medium")), "1.0")
        assertEquals("medium", s.tier)
        assertEquals(1500L, s.align.maxGapMs)
        assertTrue(s.models.containsKey("asr"))
    }
}
