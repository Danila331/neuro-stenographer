package ru.neurosteno.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.app.TestData
import ru.neurosteno.app.edit.EditableTranscript

class PromptBuilderTest {
    @Test
    fun linesUseNamesAndTime() {
        val t = EditableTranscript.of(TestData.dialog(), mapOf("speaker_2" to "Мурзик"))
        val lines = PromptBuilder.transcriptLines(t)
        assertEquals("[00:01] Мурзик: добрый день", lines[0])
        assertEquals("[00:03] Говорящий 2: здравствуйте коллеги", lines[1])
    }

    @Test
    fun chunkRespectsBudgetAndKeepsAllLines() {
        val lines = (1..20).map { "строка номер $it" }
        val chunks = PromptBuilder.chunk(lines, 60)
        assertTrue(chunks.all { it.length <= 60 })
        assertEquals(lines, chunks.flatMap { it.split('\n') })
    }

    @Test
    fun overlongLineIsTruncated() {
        val chunks = PromptBuilder.chunk(listOf("x".repeat(500)), 100)
        assertEquals(1, chunks.size)
        assertEquals(100, chunks[0].length)
    }

    @Test
    fun budgetShrinksWithContextPressure() {
        val big = PromptBuilder.contentBudgetChars(4096, 700, 300)
        val small = PromptBuilder.contentBudgetChars(2048, 700, 300)
        assertTrue(big > small)
        assertTrue(small >= 256)
    }

    @Test
    fun chatmlHasNoThinkAndRoles() {
        val p = PromptBuilder.chatml("Система", "Привет")
        assertTrue(p.startsWith("<|im_start|>system\nСистема<|im_end|>\n<|im_start|>user\nПривет<|im_end|>\n<|im_start|>assistant"))
        assertTrue(p.endsWith("<think>\n\n</think>\n\n"))
    }

    @Test
    fun cleanRemovesThinkAndTags() {
        assertEquals("Ответ", PromptBuilder.clean("<think>размышляю</think>\nОтвет<|im_end|>"))
        assertFalse(PromptBuilder.clean("<|im_start|>assistant текст").contains("<|"))
    }

    @Test
    fun cleanStripsPseudoTagsAndMarkdownBold() {
        assertEquals("Краткое резюме:\nТекст", PromptBuilder.clean("<protocol>\n\n**Краткое резюме:**\nТекст\n</protocol>"))
    }

    @Test
    fun selectRelevantPrefersMatchingChunks() {
        val chunks = listOf("обсуждали погоду и обед", "решили перенести релиз на пятницу", "говорили про отпуск", "бюджет проекта утвердили")
        val picked = PromptBuilder.selectRelevant(chunks, "Когда релиз?", 40)
        assertEquals(listOf("решили перенести релиз на пятницу"), picked)
    }

    @Test
    fun selectRelevantReturnsAllWhenFits() {
        val chunks = listOf("а", "б")
        assertEquals(chunks, PromptBuilder.selectRelevant(chunks, "вопрос", 1000))
    }
}
