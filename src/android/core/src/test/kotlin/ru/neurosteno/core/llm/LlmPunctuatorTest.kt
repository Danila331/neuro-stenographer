package ru.neurosteno.core.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.neurosteno.core.LlmAssistant
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word

class LlmPunctuatorTest {
    private fun u(text: String) = Utterance("speaker_1", 0, 1000, text, listOf(Word(text, 0, 1000)))

    private class FakeLlm(val answer: (String) -> String) : LlmAssistant {
        var calls = 0
        var closed = false
        var lastPrompt = ""
        override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
            calls++
            lastPrompt = prompt
            return answer(prompt)
        }
        override fun close() { closed = true }
    }

    @Test
    fun `numbered answer is mapped back and guarded`() {
        val llm = FakeLlm { "1. Добрый день!\n2. Как дела?" }
        val r = LlmPunctuator(llm, 10_000).punctuate(listOf(u("добрый день"), u("как дела")), { _, _ -> })
        assertEquals(listOf("Добрый день!", "Как дела?"), r.map { it.text })
        assertTrue(llm.lastPrompt.contains("1. добрый день"))
        assertTrue(llm.lastPrompt.endsWith("<think>\n\n</think>\n\n"))
    }

    @Test
    fun `changed words fall back per utterance only`() {
        val llm = FakeLlm { "1. Добрый вечер!\n2. Как дела?" }
        val r = LlmPunctuator(llm, 10_000).punctuate(listOf(u("добрый день"), u("как дела")), { _, _ -> })
        assertEquals(listOf("Добрый день.", "Как дела?"), r.map { it.text })
    }

    @Test
    fun `continuation lines and noise are handled, missing items fall back`() {
        val llm = FakeLlm { "Вот результат:\n1. Привет,\nмир!\n<|im_end|>" }
        val r = LlmPunctuator(llm, 10_000).punctuate(listOf(u("привет мир"), u("до свидания")), { _, _ -> })
        assertEquals("Привет, мир!", r[0].text)
        assertEquals("До свидания.", r[1].text)
    }

    @Test
    fun `llm exception gives fallback for the whole batch and does not propagate`() {
        val llm = FakeLlm { error("jni crashed") }
        val r = LlmPunctuator(llm, 10_000).punctuate(listOf(u("привет")), { _, _ -> })
        assertEquals("Привет.", r.single().text)
    }

    @Test
    fun `times speaker and words are never touched`() {
        val src = Utterance("speaker_2", 5, 77, "привет", listOf(Word("привет", 5, 77, 0.5f)))
        val r = LlmPunctuator(FakeLlm { "1. Привет!" }, 1000).punctuate(listOf(src), { _, _ -> }).single()
        assertEquals(src.copy(text = "Привет!"), r)
    }

    @Test
    fun `batches respect max chars and every utterance is covered once`() {
        val p = LlmPunctuator(FakeLlm { "" }, 1000, maxBatchChars = 20)
        val us = listOf(u("aaaaaaaaaa"), u("bbbbbbbbbb"), u("cccccccccc"), u("d".repeat(50)))
        val b = p.batches(us)
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3)), b)
    }

    @Test
    fun `time budget switches off llm for the rest`() {
        var clock = 0L
        val llm = FakeLlm { clock += 10_000; "1. А." }
        // бюджет 0.1 * 10_000 = 1000 мс; после первой пачки прошло 10 с -> вторая и далее без LLM
        val p = LlmPunctuator(llm, 10_000, timeBudgetRtf = 0.1, maxBatchChars = 1, nowMs = { clock })
        val r = p.punctuate(listOf(u("а"), u("б"), u("в")), { _, _ -> })
        assertEquals(1, llm.calls)
        assertEquals(listOf("А.", "Б.", "В."), r.map { it.text })
    }

    @Test
    fun `cancellation propagates`() {
        val llm = FakeLlm { throw kotlinx.coroutines.CancellationException("x") }
        org.junit.jupiter.api.assertThrows<java.util.concurrent.CancellationException> {
            LlmPunctuator(llm, 1000).punctuate(listOf(u("а")), { _, _ -> })
        }
    }

    @Test
    fun `empty input and close`() {
        val llm = FakeLlm { "" }
        val p = LlmPunctuator(llm, 1000)
        assertTrue(p.punctuate(emptyList(), { _, _ -> }).isEmpty())
        assertEquals(0, llm.calls)
        p.close()
        assertTrue(llm.closed)
    }
}
