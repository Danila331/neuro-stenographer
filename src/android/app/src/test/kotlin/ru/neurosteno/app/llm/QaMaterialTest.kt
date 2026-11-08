package ru.neurosteno.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QaMaterialTest {
    private val lines = (0 until 120).map { i -> "[%02d:%02d] Говорящий %d: ".format(i / 60, i % 60, i % 3 + 1) + (if (i == 87) "обсуждали покупку забора для дачи " else "") + "обычная реплика номер $i " + "и ещё слова ".repeat(4) }

    @Test
    fun shortTranscriptIsTakenWhole() {
        val short = lines.take(5)
        val budget = PromptBuilder.contentBudgetChars(4096, 400, 300)
        assertEquals(PromptBuilder.chunk(short, budget), PromptBuilder.qaMaterial(short, "что угодно", budget, 0.25))
    }

    @Test
    fun longTranscriptYieldsOneSmallRelevantFragment() {
        val budget = PromptBuilder.contentBudgetChars(4096, 400, 300)
        assertTrue(lines.joinToString("\n").length > budget)
        val material = PromptBuilder.qaMaterial(lines, "Что решили про покупку забора?", budget, 0.25)
        val total = material.sumOf { it.length }
        assertTrue("материал $total символов больше четверти бюджета $budget", total <= budget * 0.25 + 1)
        assertTrue(material.joinToString("\n").contains("забора"))
        // ratio = 1.0 -- прежнее поведение: фрагменты по всему бюджету
        val old = PromptBuilder.qaMaterial(lines, "Что решили про покупку забора?", budget, 1.0)
        assertTrue(old.sumOf { it.length } > total)
        assertEquals(PromptBuilder.selectRelevant(PromptBuilder.chunk(lines, budget), "Что решили про покупку забора?", budget), old)
    }

    @Test
    fun rareRelevantTermOutweighsRepeatedGenericTerms() {
        val chunks = listOf(
            "сколько платим оркестру пять тысяч рублей",
            "сколько платим за жилеты для добровольцев",
            "дворники просят поднять оплату до семидесяти рублей",
        )
        val picked = PromptBuilder.selectRelevant(chunks, "Сколько будут платить дворникам после повышения?", chunks[0].length + chunks[1].length)
        assertTrue(picked.toString(), picked.any { it.contains("семидесяти") })
    }

    @Test
    fun ratioIsClamped() {
        val budget = PromptBuilder.contentBudgetChars(4096, 400, 300)
        val tiny = PromptBuilder.qaMaterial(lines, "забора", budget, 0.0) // зажато до 5 %
        assertTrue(tiny.isNotEmpty())
        assertTrue(tiny.sumOf { it.length } <= budget * 0.05 + 300)
    }
}
