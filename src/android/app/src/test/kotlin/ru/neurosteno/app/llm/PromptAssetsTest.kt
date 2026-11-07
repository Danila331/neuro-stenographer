package ru.neurosteno.app.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Промпты приложения -- ровно финальные версии llm-bench (`src/eval/bench_llm/prompts/android/`). */
class PromptAssetsTest {
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "config/tiers").isDirectory }
    private val assets = File(repoRoot, "src/android/app/src/main/assets/prompts")
    private val bench = File(repoRoot, "src/eval/bench_llm/prompts/android")

    @Test
    fun assetsEqualBenchPrompts() {
        for (name in listOf("protocol_system.txt", "protocol_notes_system.txt", "qa_system.txt")) {
            assertEquals(name, File(bench, name).readText(Charsets.UTF_8), File(assets, name).readText(Charsets.UTF_8))
        }
    }

    @Test
    fun promptsKeepTheTranscriptLineFormatContract() {
        val p = File(assets, "protocol_system.txt").readText(Charsets.UTF_8)
        assertTrue(p.contains("[мм:сс] Говорящий N: реплика"))
        for (s in ProtocolMerge.SECTIONS) assertTrue(s, p.contains(s))
        assertTrue(File(assets, "qa_system.txt").readText(Charsets.UTF_8).contains("В расшифровке этого нет"))
    }
}
