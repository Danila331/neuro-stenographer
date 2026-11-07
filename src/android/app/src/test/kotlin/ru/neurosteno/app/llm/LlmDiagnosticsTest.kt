package ru.neurosteno.app.llm

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmDiagnosticsTest {
    private lateinit var dir: java.io.File
    private val run = LlmRunMetrics(
        kind = "protocol", model = "qwen3-1.7b.gguf", promptTokens = 210,
        generatedTokens = 42, tokPerSecond = 7.5, durationMs = 5600,
        peakPssMb = 768, status = "ok",
    )

    @Before fun setUp() { dir = Files.createTempDirectory("llm-results").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun formatsLogcatLine() {
        assertEquals(
            "llm_done kind=protocol model=qwen3-1.7b.gguf prompt_tokens=210 gen_tokens=42 tok_per_s=7.50 ms=5600 peak_pss_mb=768 status=ok",
            LlmDiagnostics.doneLine(run),
        )
    }

    @Test
    fun writesAtomicLlmMarkerBesideResult() {
        val marker = LlmDiagnostics.writeMarker(dir, "встреча.m4a", run, finishedAtMs = 123).getOrThrow()
        assertEquals("встреча.llm.json", marker.name)
        val json = Json.parseToJsonElement(marker.readText()).jsonObject
        assertEquals("встреча.m4a", json["source"]!!.jsonPrimitive.content)
        assertEquals("123", json["finished_at_ms"]!!.jsonPrimitive.content)
        assertEquals("protocol", json["kind"]!!.jsonPrimitive.content)
        assertEquals("qwen3-1.7b.gguf", json["model"]!!.jsonPrimitive.content)
        assertEquals("210", json["prompt_tokens"]!!.jsonPrimitive.content)
        assertTrue(json["prompt_tokens_estimated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("42", json["gen_tokens"]!!.jsonPrimitive.content)
        assertEquals("768", json["peak_pss_mb"]!!.jsonPrimitive.content)
        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertFalse(java.io.File(dir, "встреча.llm.json.tmp").exists())
    }

    @Test
    fun logsAndMarksAppliedLlamaOptionsWhenKnown() {
        val withOpts = run.copy(options = "no_repack=1 mmap=1 flash_attn=enabled kv=q8_0 n_ctx=4096 threads=4")
        assertTrue(LlmDiagnostics.doneLine(withOpts).endsWith(" llama_opts=\"no_repack=1 mmap=1 flash_attn=enabled kv=q8_0 n_ctx=4096 threads=4\""))
        val marker = LlmDiagnostics.writeMarker(dir, "a.wav", withOpts, finishedAtMs = 1).getOrThrow()
        assertEquals("no_repack=1 mmap=1 flash_attn=enabled kv=q8_0 n_ctx=4096 threads=4", Json.parseToJsonElement(marker.readText()).jsonObject["llama_options"]!!.jsonPrimitive.content)
    }
}
