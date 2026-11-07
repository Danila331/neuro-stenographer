package ru.neurosteno.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextGuardTest {
    @Test
    fun `norm lowercases maps yo removes punctuation and collapses spaces`() {
        assertEquals("все хорошо привет", TextGuard.norm("  Всё  хорошо, Привет!!! "))
        assertEquals("неа 5 раз", TextGuard.norm("не-а, 5 раз"))
        assertEquals("", TextGuard.norm("...!?"))
    }

    @Test
    fun `same words ignores punctuation case and yo`() {
        assertTrue(TextGuard.sameWords("добрый день как дела", "Добрый день! Как дела?"))
        assertTrue(TextGuard.sameWords("еще раз", "Ещё раз."))
        assertFalse(TextGuard.sameWords("добрый день", "Добрый вечер."))
        assertFalse(TextGuard.sameWords("добрый день", "Добрый."))
        assertFalse(TextGuard.sameWords("добрый день", "Добрый добрый день."))
    }

    @Test
    fun `finalize capitalizes and adds period`() {
        assertEquals("Привет мир.", TextGuard.finalize("привет мир"))
        assertEquals("Привет мир?", TextGuard.finalize("привет мир?"))
        assertEquals("", TextGuard.finalize("  "))
    }

    @Test
    fun `guardText keeps good output and falls back on bad output`() {
        assertEquals("Привет, мир!", TextGuard.guardText("привет мир", "Привет, мир!"))
        assertEquals("Привет мир.", TextGuard.guardText("привет мир", "Здравствуй, мир!"))
        assertEquals("Привет мир.", TextGuard.guardText("привет мир", ""))
    }

    private fun u(text: String) = Utterance("speaker_1", 0, 1000, text, listOf(Word(text, 0, 1000)))

    private class FakePunct(val f: (List<Utterance>) -> List<Utterance>) : Punctuator {
        var closed = false
        override fun punctuate(utterances: List<Utterance>, progress: ProgressListener) = f(utterances)
        override fun close() { closed = true }
    }

    @Test
    fun `guarded punctuator keeps times and words and rejects word changes`() {
        val inner = FakePunct { us ->
            us.map { if (it.text == "привет мир") it.copy(text = "Привет, мир!", startMs = 5) else it.copy(text = "Совсем другое.") }
        }
        val out = GuardedPunctuator(inner).punctuate(listOf(u("привет мир"), u("как дела")), { _, _ -> })
        assertEquals("Привет, мир!", out[0].text)
        assertEquals(0L, out[0].startMs) // времена берутся из входа
        assertEquals("Как дела.", out[1].text)
    }

    @Test
    fun `guarded punctuator survives exception and size mismatch`() {
        val boom = GuardedPunctuator(FakePunct { error("llm crashed") })
        assertEquals("Привет.", boom.punctuate(listOf(u("привет")), { _, _ -> })[0].text)
        val mismatch = GuardedPunctuator(FakePunct { emptyList() })
        assertEquals("Привет.", mismatch.punctuate(listOf(u("привет")), { _, _ -> })[0].text)
    }

    @Test
    fun `guarded punctuator without inner is identity`() {
        val src = listOf(u("привет"))
        assertEquals(src, GuardedPunctuator(null).punctuate(src, { _, _ -> }))
    }

    @Test
    fun `guarded punctuator propagates cancellation and closes inner`() {
        val inner = FakePunct { throw kotlinx.coroutines.CancellationException("cancel") }
        val g = GuardedPunctuator(inner)
        org.junit.jupiter.api.assertThrows<kotlinx.coroutines.CancellationException> { g.punctuate(listOf(u("a")), { _, _ -> }) }
        g.close()
        assertTrue(inner.closed)
    }

    @Test
    fun `normalizer applies yo and case options`() {
        assertEquals("все", TextNormalizer.apply("всё", NormalizationConfig(yoToE = true)))
        assertEquals("всё", TextNormalizer.apply("всё", NormalizationConfig()))
        assertEquals("привет", TextNormalizer.apply("Привет", NormalizationConfig(case = "lower")))
    }
}
