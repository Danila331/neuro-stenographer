package ru.neurosteno.app.llm

import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.ui.Format

/** Сборка запросов к локальной LLM (чистые функции, тестируются на JVM). Формат -- ChatML (Qwen) без «размышлений». */
object PromptBuilder {
    /** Оценка: сколько символов русского текста приходится на токен (с запасом; реальное значение ≈ 2,5–3). */
    const val CHARS_PER_TOKEN = 2.3

    /** Строки расшифровки: «[00:12] Мурзик: текст». */
    fun transcriptLines(t: EditableTranscript): List<String> =
        t.utterances.map { "[${Format.clock(it.startMs)}] ${t.displayName(it.speakerId)}: ${it.text}" }

    /** Сколько символов расшифровки помещается в контекст [nCtx] при ответе до [maxNewTokens] токенов и системной части [systemChars]. */
    fun contentBudgetChars(nCtx: Int, maxNewTokens: Int, systemChars: Int): Int {
        val tokens = nCtx - maxNewTokens - 48 - (systemChars / CHARS_PER_TOKEN).toInt()
        return (tokens.coerceAtLeast(256) * CHARS_PER_TOKEN).toInt()
    }

    /** Режет строки на фрагменты не длиннее [budgetChars] (строка не разрывается; слишком длинная строка обрезается). */
    fun chunk(lines: List<String>, budgetChars: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (raw in lines) {
            val line = if (raw.length > budgetChars) raw.take(budgetChars - 1) + "…" else raw
            if (cur.isNotEmpty() && cur.length + line.length + 1 > budgetChars) {
                out += cur.toString()
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /**
     * Материал для вопроса (B48, `qa_chunk_budget_ratio`). Помещается весь -- берётся целиком. Иначе расшифровка режется на фрагменты по
     * [ratio] от [budgetChars] (0.25 -- около 1000 токенов на запрос вместо ~3000: 2–3 крупных куска, из которых раньше выбирался один),
     * и выбираются самые подходящие вопросу, суммарно не больше размера одного фрагмента.
     */
    fun qaMaterial(lines: List<String>, question: String, budgetChars: Int, ratio: Double): List<String> {
        val whole = chunk(lines, budgetChars)
        if (whole.sumOf { it.length } <= budgetChars) return whole
        val fragBudget = (budgetChars * ratio.coerceIn(0.05, 1.0)).toInt().coerceAtLeast(200)
        return selectRelevant(chunk(lines, fragBudget), question, fragBudget)
    }

    fun chatml(system: String, user: String): String = buildString {
        append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user.trim()).append("<|im_end|>\n")
        append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    /** Убирает служебные теги и «размышления», если модель их всё же вывела. */
    fun clean(raw: String): String = raw
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace("<|im_end|>", "")
        .replace("<|im_start|>", "")
        .replace(Regex("</?[A-Za-z_][A-Za-z0-9_]*>"), "") // «<protocol>» и подобные теги, которые подражающая промпту модель добавляет сама
        .replace("**", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private val RUNAWAY = Regex("\\n\\s*(Вопрос|Question|Q)\\s*:")

    /**
     * Небольшие модели после ответа иногда сами придумывают следующий вопрос («Вопрос: ... Ответ: ...»): возвращает позицию,
     * с которой текст нужно отбросить, или -1.
     */
    fun runawayIndex(answer: String): Int {
        val m = RUNAWAY.find(answer) ?: return -1
        return if (m.range.first >= 12) m.range.first else -1
    }

    /**
     * Для вопроса выбирает самые подходящие фрагменты (по пересечению слов вопроса и фрагмента, сравнение по основе из 5 букв),
     * укладывая их в [budgetChars]; порядок фрагментов сохраняется по времени.
     */
    fun selectRelevant(chunks: List<String>, question: String, budgetChars: Int): List<String> {
        if (chunks.sumOf { it.length } <= budgetChars) return chunks
        val keys = stems(question)
        val scored = chunks.mapIndexed { i, c ->
            val cs = stems(c)
            i to keys.count { it in cs }
        }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
        val chosen = ArrayList<Int>()
        var used = 0
        for ((i, _) in scored) {
            if (used + chunks[i].length > budgetChars) continue
            chosen += i
            used += chunks[i].length
        }
        if (chosen.isEmpty()) chosen += scored.first().first
        return chosen.sorted().map { chunks[it] }
    }

    private fun stems(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase().replace('ё', 'е')).map { it.value }.filter { it.length >= 4 }.map { it.take(5) }.toSet()
}
