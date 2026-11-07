package ru.neurosteno.core.summary

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.neurosteno.core.TestPaths
import ru.neurosteno.core.TierConfig
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.search.TextEmbedder

class SummaryEngineTest {
    private fun utt(i: Int, spk: Int, text: String) = Utterance("speaker_$spk", i * 4000L, i * 4000L + 3000, text, emptyList())

    private val dialog = listOf(
        utt(0, 1, "Давайте обсудим бюджет на рыбу за квартал."),
        utt(1, 2, "В июле потратили восемь тысяч четыреста рублей."),
        utt(2, 1, "А в августе потратили одиннадцать тысяч двести рублей."),
        utt(3, 2, "Выросло из-за праздника, купили сёмгу."),
        utt(4, 1, "Предлагаю сократить закупку сёмги вдвое."),
        utt(5, 2, "Я подготовлю другой расчёт к пятнице."),
        utt(6, 1, "Теперь поговорим про ремонт крыши над складом."),
        utt(7, 2, "Мастер называл сумму двадцать тысяч рублей на ремонт крыши."),
        utt(8, 1, "В кассе только пятнадцать тысяч, не хватает пяти тысяч."),
        utt(9, 2, "Давайте устроим сбор по триста рублей с каждого."),
    )

    /** LLM-заглушка: на каждый фрагмент отвечает по грамматике-подсказке (читает номера строк из запроса). */
    private class FakeLlm : ConstrainedLlm {
        val grammars = ArrayList<String?>()
        val prompts = ArrayList<String>()
        override fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit) = generateConstrained(prompt, maxTokens, null, false, onToken)
        override fun generateConstrained(prompt: String, maxTokens: Int, grammar: String?, reusePrefix: Boolean, onToken: (String) -> Unit): String {
            grammars += grammar
            prompts += prompt
            assertTrue(reusePrefix)
            if (grammar == null) return "  Обсуждали бюджет и ремонт крыши.\n"
            val lines = prompt.substringAfter("<|im_start|>user\n").substringBefore("<|im_end|>").lines()
            val first = Regex("""\[(\d+)] \S+ Говорящий (\d+): (.*)""").matchEntire(lines.first())!!
            onToken("x")
            return """{"title":"Тема","points":[{"speaker":${first.groupValues[2]},"refs":[${first.groupValues[1]}],"text":${quote(first.groupValues[3])}}],"decisions":[],"tasks":[]}"""
        }
        override fun close() {}
        private fun quote(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
    }

    private val prompts = SummaryPrompts("SYS", "OVERVIEW")

    @Test
    fun `summarize without embedder uses lexical valleys, partial results and overview`() {
        val cfg = SummaryConfig(fragmentChars = 300, segmenter = SegmenterConfig(minUtts = 2, minChars = 50))
        val partial = ArrayList<SummaryDoc>()
        val steps = ArrayList<String>()
        val llm = FakeLlm()
        val r = SummaryEngine(cfg, prompts).summarize(dialog, null, llm, "m", { st, _, _ -> steps += st }, { partial += it })
        assertTrue(r.doc.fragments >= 2, "фрагментов: ${r.doc.fragments}")
        assertEquals(r.doc.fragments, partial.size)
        assertTrue(partial.all { !it.done } && r.doc.done)
        assertEquals("Обсуждали бюджет и ремонт крыши.", r.doc.overview)
        assertEquals(0, r.stats.parseFailed)
        assertTrue(r.doc.topics.isNotEmpty())
        // времена и номера берутся из реплик
        for (t in r.doc.topics) for (p in t.points) for (ref in p.refs) {
            assertEquals(dialog[ref.n - 1].startMs, ref.t)
            assertEquals(dialog[ref.n - 1].speakerId, ref.speaker)
            assertEquals(p.speaker, ref.speaker)
        }
        assertTrue("overview" in steps && "fragment" in steps)
        assertTrue(llm.grammars.dropLast(1).all { it != null && it.startsWith("root ::=") }) // overview -- без грамматики
        assertEquals(null, llm.grammars.last())
        assertTrue(llm.prompts.first().startsWith("<|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\n[1] 00:00 Говорящий 1:"))
    }

    @Test
    fun `embedder vectors are used when given`() {
        var calls = 0
        val emb = object : TextEmbedder {
            override val dim = 3
            override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
                calls++
                assertFalse(isQuery)
                return texts.indices.map { if (it < 6) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f) }
            }
        }
        val cfg = SummaryConfig(fragmentChars = 1500, segmenter = SegmenterConfig(k = 2, minUtts = 2, minChars = 50))
        val doc = SummaryEngine(cfg, prompts).extractive(dialog, emb)
        assertEquals(1, calls)
        assertEquals("extractive", doc.mode)
        assertEquals(2, doc.topics.size) // граница между репликами 5 и 6 (индекс 6)
        assertEquals(dialog[6].startMs, doc.topics[1].startMs)
    }

    @Test
    fun `interrupted thread cancels between fragments`() {
        val llm = FakeLlm()
        Thread.currentThread().interrupt()
        try {
            SummaryEngine(SummaryConfig(fragmentChars = 300), prompts).summarize(dialog, null, llm)
            error("ожидалась отмена")
        } catch (e: java.util.concurrent.CancellationException) {
            assertTrue(llm.prompts.isEmpty())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `tier configs carry the summary block`() {
        val dir = File(TestPaths.androidRoot, "../../config/tiers")
        for (tier in listOf("lite", "medium", "hard")) {
            val cfg = TierConfig.parse(File(dir, "$tier.json").readText())
            assertEquals(if (tier == "lite") "extractive" else "llm", cfg.summary.mode, tier)
            assertEquals(1500, cfg.summary.fragmentChars)
            assertEquals(0.0, cfg.summary.segmenter.alpha)
            assertTrue(cfg.summary.showExtractiveFirst)
        }
        assertTrue(TierConfig.parse(File(dir, "hard.json").readText()).summary.auto)
    }

    @Test
    fun `empty transcript gives empty documents`() {
        val e = SummaryEngine(SummaryConfig(), prompts)
        assertEquals(0, e.extractive(emptyList()).topics.size)
        assertEquals(0, e.summarize(emptyList(), null, FakeLlm()).doc.topics.size)
    }
}
