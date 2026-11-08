package ru.neurosteno.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolFlowTest {
    private val system = "СИСТЕМА ПРОТОКОЛА"
    private val notes = "СИСТЕМА КОНСПЕКТА"

    /** Фейковая LLM: на каждый запрос отвечает по очереди заранее заданными текстами, отдавая их кусочками по 7 символов. */
    private class FakeLlm(private val answers: List<String>) {
        val prompts = ArrayList<String>()
        val maxTokens = ArrayList<Int>()
        fun generate(prompt: String, max: Int, onPiece: (String) -> Unit): String {
            val a = answers[prompts.size]
            prompts += prompt
            maxTokens += max
            a.chunked(7).forEach(onPiece)
            return a
        }
    }

    private fun lines(n: Int, words: Int = 12) = (1 until n + 1).map { i -> "[%02d:%02d] Говорящий %d: ".format(i / 60, i % 60, i % 2 + 1) + (1..words).joinToString(" ") { "слово$i" } }

    private val protocolA = "Резюме: Обсудили A.\nВопросы:\n- Тема A [00:01]\nРешения:\n- нет\nПоручения:\n- нет\nЦитаты:\n- «слово1 слово1 слово1» (Говорящий 2, [00:01])"
    private val protocolB = "Резюме: Обсудили B.\nВопросы:\n- Тема B [05:00]\nРешения:\n- Решили B [05:10]\nПоручения:\n- Говорящий 1: сделать B [05:20]\nЦитаты:\n- «выдуманная фраза которой нет» (Говорящий 1, [05:30])"

    @Test
    fun shortRecordIsOneRequestWithProtocolPromptAnd450Tokens() {
        val llm = FakeLlm(listOf(protocolA))
        val seen = ArrayList<String>()
        val out = ProtocolFlow(4096, chunkMerge = true, filterQuotes = false, system = system, notesSystem = notes)
            .run(lines(5), emptyList(), { p, m, cb -> llm.generate(p, m, cb) }, onText = { seen += it })
        assertEquals(protocolA, out)
        assertEquals(1, llm.prompts.size)
        assertEquals(450, llm.maxTokens[0])
        assertTrue(llm.prompts[0].contains("<|im_start|>system\n$system<|im_end|>"))
        assertTrue(llm.prompts[0].contains("Расшифровка (часть 1 из 1):\n[00:01] Говорящий 2:"))
        assertTrue(llm.prompts[0].endsWith("<think>\n\n</think>\n\n"))
        assertEquals(protocolA, seen.last()) // потоковый вывод закончился итоговым текстом
        assertTrue(seen.size > 3)
    }

    @Test
    fun longRecordIsSplitIntoFragmentsAndMergedInCode() {
        val l = lines(60, words = 15)
        val budget = minOf(PromptBuilder.contentBudgetChars(2048, 450, system.length), 2500)
        val expectedFragments = PromptBuilder.chunk(l, budget).size
        assertTrue(expectedFragments >= 2)
        val llm = FakeLlm(List(expectedFragments) { if (it % 2 == 0) protocolA else protocolB })
        val statuses = ArrayList<String>()
        val prompts = ArrayList<String>()
        var pieces = 0
        val out = ProtocolFlow(2048, chunkMerge = true, filterQuotes = false, system = system, notesSystem = notes)
            .run(l, emptyList(), { p, m, cb -> llm.generate(p, m, cb) }, status = { statuses += it }, onPrompt = { prompts += it }, onPiece = { pieces++ })
        assertEquals(expectedFragments, llm.prompts.size)
        assertTrue(llm.maxTokens.all { it == 450 })
        for (i in 0 until expectedFragments) {
            assertTrue(llm.prompts[i].contains("Расшифровка (часть ${i + 1} из $expectedFragments):\n"))
            assertEquals("Протокол: часть ${i + 1} из $expectedFragments…", statuses[i])
        }
        assertEquals(llm.prompts, prompts)
        assertTrue(pieces > 0)
        // слияние: резюме склеены, пункты без повторов, «нет» отброшен
        assertEquals(ProtocolMerge.mergeProtocols(List(expectedFragments) { if (it % 2 == 0) protocolA else protocolB }), out)
        assertTrue(out.startsWith("Резюме: Обсудили A. Обсудили B."))
        assertEquals(1, Regex("Тема A").findAll(out).count())
    }

    @Test
    fun filterQuotesDropsNonVerbatimQuotesOnly() {
        val transcriptTexts = listOf("слово1 слово1 слово1 слово1")
        val llm = FakeLlm(listOf(protocolB.replace("выдуманная фраза которой нет", "слово1 слово1 слово1").let { it + "\n- «а этой цитаты в тексте нет совсем» (Говорящий 2)" }))
        val out = ProtocolFlow(4096, true, true, system, notes).run(lines(3), transcriptTexts, { p, m, cb -> llm.generate(p, m, cb) })
        assertTrue(out.contains("«слово1 слово1 слово1»"))
        assertFalse(out.contains("а этой цитаты"))
        assertTrue(out.contains("- Решили B [05:10]")) // остальные пункты не тронуты
        // без фильтра -- цитата остаётся
        val llm2 = FakeLlm(listOf("Цитаты:\n- «а этой цитаты в тексте нет совсем» (Говорящий 2)"))
        assertTrue(ProtocolFlow(4096, true, false, system, notes).run(lines(3), transcriptTexts, { p, m, cb -> llm2.generate(p, m, cb) }).contains("а этой цитаты"))
    }

    @Test
    fun legacyModeStillUsesNotesThenProtocol() {
        val l = lines(60, words = 15)
        val frag = PromptBuilder.chunk(l, PromptBuilder.contentBudgetChars(2048, 320, notes.length)).size
        assertTrue(frag >= 2)
        val llm = FakeLlm(List(frag) { "- пункт конспекта $it" } + listOf("Итоговый протокол"))
        val out = ProtocolFlow(2048, chunkMerge = false, filterQuotes = false, system = system, notesSystem = notes).run(l, emptyList(), { p, m, cb -> llm.generate(p, m, cb) })
        assertEquals("Итоговый протокол", out)
        assertEquals(frag + 1, llm.prompts.size)
        assertTrue(llm.prompts.last().contains("Часть 1:\n- пункт конспекта 0"))
        assertEquals(700, llm.maxTokens.last())
        assertEquals(320, llm.maxTokens.first())
    }

    @Test
    fun longRecordUsesFourFocusedFragments() {
        val longLines = List(24) { i -> "[00:${(i % 60).toString().padStart(2, '0')}] Говорящий 1: " + "я".repeat(380) }
        val llm = FakeLlm(List(4) { protocolA })
        ProtocolFlow(4096, chunkMerge = true, filterQuotes = false, system = system, notesSystem = notes)
            .run(longLines, emptyList(), { p, m, cb -> llm.generate(p, m, cb) })
        assertEquals(4, llm.prompts.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyTranscriptIsRejected() {
        ProtocolFlow(4096, true, true, system, notes).run(emptyList(), emptyList(), { _, _, _ -> "" })
    }
}
