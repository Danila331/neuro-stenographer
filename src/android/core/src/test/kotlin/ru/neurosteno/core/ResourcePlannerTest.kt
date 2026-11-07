package ru.neurosteno.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResourcePlannerTest {
    private val medium = TierConfig(
        tier = "medium",
        numThreads = 4,
        llm = LlmConfig(model = "llm/q.gguf", punctuation = true),
        punctuation = PunctuationConfig(source = "llm"),
    )
    private val hard = medium.copy(
        tier = "hard",
        numThreads = 6,
        asr = AsrConfig(secondPass = SecondPassConfig(modelDir = "asr/whisper")),
        audio = AudioConfig(denoise = DenoiseConfig(enabled = true, model = "d.onnx")),
    )

    @Test
    fun `plenty of memory keeps everything`() {
        val p = ResourcePlanner.plan(MemorySnapshot(4096, 3000), medium, cores = 4)
        assertEquals(4, p.numThreads)
        assertTrue(p.llmPunctuation)
        assertFalse(p.degraded)
    }

    @Test
    fun `threads are capped by cores`() {
        val p = ResourcePlanner.plan(MemorySnapshot(4096, 3000), medium, cores = 2)
        assertEquals(2, p.numThreads)
        assertTrue(p.degraded)
    }

    @Test
    fun `moderate shortage halves threads and drops optional heavy parts`() {
        val p = ResourcePlanner.plan(MemorySnapshot(8192, 3800), hard, cores = 8) // нужно ~4000
        assertEquals(3, p.numThreads)
        assertFalse(p.secondPass)
        assertFalse(p.denoise)
        assertTrue(p.degraded)
    }

    @Test
    fun `severe shortage or low memory flag gives minimal plan`() {
        val severe = ResourcePlanner.plan(MemorySnapshot(2048, 700), medium, cores = 4) // < 0.6 * 1500
        assertEquals(1, severe.numThreads)
        assertFalse(severe.llmPunctuation)
        val lowFlag = ResourcePlanner.plan(MemorySnapshot(4096, 3000, lowMemory = true), medium, cores = 4)
        assertEquals(1, lowFlag.numThreads)
        assertFalse(lowFlag.llmPunctuation)
    }

    @Test
    fun `llm is skipped when it does not fit even if main pass fits`() {
        val p = ResourcePlanner.plan(MemorySnapshot(4096, 1700), medium, cores = 4) // основной проход ок, LLM нужно ~2250
        assertEquals(4, p.numThreads)
        assertFalse(p.llmPunctuation)
        assertTrue(p.reasons.single().contains("LLM"))
    }

    @Test
    fun `llm off in config stays off and zero memory never throws`() {
        val noLlm = medium.copy(llm = LlmConfig(model = null))
        assertFalse(ResourcePlanner.plan(MemorySnapshot(4096, 4000), noLlm, cores = 4).llmPunctuation)
        val p = ResourcePlanner.plan(MemorySnapshot(0, 0), medium, cores = 0)
        assertEquals(1, p.numThreads)
    }

    @Test
    fun `jury 2gb and 4gb emulator scenarios`() {
        // AVD 2 ГБ: после системы доступно ~900 МБ -> lite-план (peak 1000) частично урезан, но работает; LLM выключена
        val lite = TierConfig(tier = "lite", numThreads = 4, llm = LlmConfig(model = "llm/q.gguf"), punctuation = PunctuationConfig("llm"))
        val p2 = ResourcePlanner.plan(MemorySnapshot(2048, 900), lite, cores = 2)
        assertTrue(p2.numThreads in 1..2)
        assertFalse(p2.llmPunctuation)
        assertFalse(ResourcePlanner.plan(MemorySnapshot(2400, 1500), lite, cores = 2).llmPunctuation)
        // AVD 4 ГБ: доступно ~2300 МБ -> medium (peak 1500) идёт без урезаний по памяти, LLM 1800*1.25=2250 влезает
        val p4 = ResourcePlanner.plan(MemorySnapshot(4096, 2300), medium, cores = 4)
        assertEquals(4, p4.numThreads)
        assertTrue(p4.llmPunctuation)
    }

    private val withLive = medium.copy(live = LiveConfig(enabled = true, modelDir = "asr/streaming-zipformer-small-ru-int8", numThreads = 2))

    @Test
    fun `live preview enabled with enough memory and cores`() {
        val p = ResourcePlanner.planLive(MemorySnapshot(4096, 2500), withLive, cores = 8)
        assertTrue(p.enabled)
        assertEquals(2, p.numThreads)
    }

    @Test
    fun `live preview threads never exceed half of cores`() {
        assertEquals(1, ResourcePlanner.planLive(MemorySnapshot(4096, 2500), withLive, cores = 2).numThreads)
        assertEquals(2, ResourcePlanner.planLive(MemorySnapshot(4096, 2500), withLive, cores = 4).numThreads)
    }

    @Test
    fun `live preview is off when config model memory or cores say so`() {
        assertFalse(ResourcePlanner.planLive(MemorySnapshot(4096, 2500), medium, cores = 8).enabled) // нет блока live
        assertFalse(ResourcePlanner.planLive(MemorySnapshot(4096, 2500), withLive, cores = 8, modelPresent = false).enabled)
        assertFalse(ResourcePlanner.planLive(MemorySnapshot(4096, 300), withLive, cores = 8).enabled)
        assertFalse(ResourcePlanner.planLive(MemorySnapshot(1024, 800), withLive, cores = 8).enabled)
        assertFalse(ResourcePlanner.planLive(MemorySnapshot(4096, 2500, lowMemory = true), withLive, cores = 8).enabled)
        assertFalse(ResourcePlanner.planLive(MemorySnapshot(4096, 2500), withLive, cores = 1).enabled)
        assertTrue(ResourcePlanner.planLive(MemorySnapshot(4096, 300), withLive, cores = 8).reason!!.contains("свободной памяти"))
    }
}
