package ru.neurosteno.core.llm

import ru.neurosteno.core.LlmAssistant
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.Punctuator
import ru.neurosteno.core.Stage
import ru.neurosteno.core.TextGuard
import ru.neurosteno.core.Utterance

/**
 * Пунктуация и регистр через локальную LLM (ChatML-промпт, подходит для Qwen). Реплики идут пачками
 * «1. текст / 2. текст»; ответ разбирается по номерам. Результат каждой реплики проверяется [TextGuard]
 * (слова не меняются), при любом сбое пачка/реплика получают безопасный fallback ([TextGuard.finalize]).
 *
 * @param audioDurationMs длительность записи -- для бюджета времени: если стадия съела больше
 *        `timeBudgetRtf * audioDurationMs` (0 = без лимита), остальные реплики идут без LLM.
 */
class LlmPunctuator(
    private val llm: LlmAssistant,
    private val audioDurationMs: Long,
    private val timeBudgetRtf: Double = 0.0,
    private val maxBatchChars: Int = 700,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Punctuator {

    override fun punctuate(utterances: List<Utterance>, progress: ProgressListener): List<Utterance> {
        if (utterances.isEmpty()) return utterances
        val out = utterances.toMutableList()
        val started = nowMs()
        val budgetMs = if (timeBudgetRtf > 0) (timeBudgetRtf * audioDurationMs).toLong() else Long.MAX_VALUE
        var done = 0
        for (batch in batches(utterances)) {
            progress.onProgress(Stage.PUNCTUATION, done.toFloat() / utterances.size)
            val overBudget = nowMs() - started > budgetMs
            val results: Map<Int, String> = if (overBudget) emptyMap() else runBatch(batch.map { utterances[it].text })
            batch.forEachIndexed { k, idx ->
                val u = utterances[idx]
                out[idx] = u.copy(text = TextGuard.guardText(u.text, results[k + 1] ?: ""))
            }
            done += batch.size
        }
        progress.onProgress(Stage.PUNCTUATION, 1f)
        return out
    }

    /** Индексы реплик, сгруппированные в пачки не длиннее [maxBatchChars] (минимум одна реплика на пачку). */
    internal fun batches(utterances: List<Utterance>): List<List<Int>> {
        val res = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var len = 0
        utterances.forEachIndexed { i, u ->
            if (cur.isNotEmpty() && len + u.text.length > maxBatchChars) {
                res += cur; cur = ArrayList(); len = 0
            }
            cur += i
            len += u.text.length
        }
        if (cur.isNotEmpty()) res += cur
        return res
    }

    private fun runBatch(texts: List<String>): Map<Int, String> {
        val prompt = buildPrompt(texts)
        val budgetTokens = (texts.sumOf { it.length } / 2 + 32 * texts.size + 64).coerceAtMost(1024)
        val raw = try {
            llm.generate(prompt, budgetTokens)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }
        return parse(raw, texts.size)
    }

    internal fun buildPrompt(texts: List<String>): String = buildString {
        append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
        append("<|im_start|>user\n")
        texts.forEachIndexed { i, t -> append(i + 1).append(". ").append(t).append('\n') }
        append("<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    /** Разбор «N. текст» построчно; строки без номера дописываются к предыдущему пункту. */
    internal fun parse(raw: String, expected: Int): Map<Int, String> {
        val res = LinkedHashMap<Int, StringBuilder>()
        var current = -1
        for (line in raw.replace("<|im_end|>", "").lines()) {
            val m = NUMBERED.matchEntire(line.trim())
            if (m != null) {
                current = m.groupValues[1].toInt()
                if (current in 1..expected) res.getOrPut(current) { StringBuilder() }.append(m.groupValues[2].trim())
            } else if (current in 1..expected && line.isNotBlank()) {
                res[current]!!.append(' ').append(line.trim())
            }
        }
        return res.mapValues { it.value.toString().trim() }
    }

    override fun close() = llm.close()

    private companion object {
        val NUMBERED = Regex("""(\d{1,3})[.)]\s*(.*)""")
        const val SYSTEM = "Ты редактор расшифровок русской речи. Для каждой пронумерованной строки расставь знаки " +
            "препинания и заглавные буквы. Слова не добавляй, не удаляй, не заменяй и не меняй порядок. " +
            "Ответь теми же номерами, по одной строке на пункт, без пояснений."
    }
}
