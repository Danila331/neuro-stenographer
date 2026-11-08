package ru.neurosteno.app.llm

/**
 * Построение протокола заседания поверх любой генерации (чистая логика, тестируется на JVM с фейковой LLM).
 *
 * * `chunkMerge = true` (B48): запись режется на фрагменты по бюджету контекста, для каждого вызывается тот же `protocol_system.txt`
 *   (до [FRAGMENT_MAX_TOKENS] токенов), разделы сливаются в коде ([ProtocolMerge.mergeProtocols]);
 * * иначе -- прежний режим «конспект каждого фрагмента -> протокол по конспектам».
 * Если [filterQuotes] -- в конце выбрасываются цитаты, которых нет в расшифровке дословно.
 */
class ProtocolFlow(
    private val nCtx: Int,
    private val chunkMerge: Boolean,
    private val filterQuotes: Boolean,
    private val system: String,
    private val notesSystem: String,
) {
    /** Что происходит сейчас: «Протокол: часть 2 из 3…». */
    fun interface Status { fun onStatus(text: String) }

    /** Генерация: (запрос, максимум токенов, колбэк кусочков текста) -> весь текст. */
    fun interface Generate { fun generate(prompt: String, maxTokens: Int, onPiece: (String) -> Unit): String }

    /**
     * @param lines строки расшифровки «[мм:сс] Говорящий N: текст»
     * @param utteranceTexts тексты реплик (для фильтра цитат)
     * @param onPrompt вызывается с каждым собранным запросом (подсчёт токенов в диагностике)
     * @param onPiece каждый сгенерированный кусочек (подсчёт токенов)
     * @param onText текущий видимый текст протокола (потоковый вывод)
     */
    fun run(
        lines: List<String>,
        utteranceTexts: List<String>,
        generate: Generate,
        status: Status = Status {},
        onPrompt: (String) -> Unit = {},
        onPiece: () -> Unit = {},
        onText: (String) -> Unit = {},
    ): String {
        require(lines.isNotEmpty()) { "В расшифровке нет реплик" }
        val text = if (chunkMerge) runChunkMerge(lines, generate, status, onPrompt, onPiece, onText) else runNotes(lines, generate, status, onPrompt, onPiece, onText)
        return if (filterQuotes) ProtocolMerge.filterQuotes(text, ProtocolMerge.normTranscript(utteranceTexts)) else text
    }

    private fun runChunkMerge(
        lines: List<String>, generate: Generate, status: Status, onPrompt: (String) -> Unit, onPiece: () -> Unit, onText: (String) -> Unit,
    ): String {
        val budget = minOf(PromptBuilder.contentBudgetChars(nCtx, FRAGMENT_MAX_TOKENS, system.length), FRAGMENT_BUDGET_CHARS)
        val frags = PromptBuilder.chunk(lines, budget)
        val outs = ArrayList<String>()
        frags.forEachIndexed { i, part ->
            status.onStatus(if (frags.size == 1) "Составление протокола…" else "Протокол: часть ${i + 1} из ${frags.size}…")
            val prompt = PromptBuilder.chatml(system, "Расшифровка (часть ${i + 1} из ${frags.size}):\n$part")
            onPrompt(prompt)
            val sb = StringBuilder()
            generate.generate(prompt, FRAGMENT_MAX_TOKENS) { piece ->
                onPiece()
                sb.append(piece)
                val live = PromptBuilder.clean(sb.toString())
                // потоковый вывод: готовые части уже слиты, текущая показывается как есть
                onText(if (outs.isEmpty()) live else ProtocolMerge.mergeProtocols(outs + live))
            }
            outs += PromptBuilder.clean(sb.toString())
        }
        return if (outs.size == 1) outs[0] else ProtocolMerge.mergeProtocols(outs)
    }

    private fun runNotes(
        lines: List<String>, generate: Generate, status: Status, onPrompt: (String) -> Unit, onPiece: () -> Unit, onText: (String) -> Unit,
    ): String {
        val budget = PromptBuilder.contentBudgetChars(nCtx, PROTOCOL_MAX_TOKENS, system.length)
        val chunks = PromptBuilder.chunk(lines, budget)
        var material = chunks.joinToString("\n")
        if (chunks.size > 1) {
            val notesBudget = PromptBuilder.contentBudgetChars(nCtx, NOTES_MAX_TOKENS, notesSystem.length)
            val frag = PromptBuilder.chunk(lines, notesBudget)
            val notes = ArrayList<String>()
            frag.forEachIndexed { i, part ->
                status.onStatus("Конспект части ${i + 1} из ${frag.size}…")
                val prompt = PromptBuilder.chatml(notesSystem, "Фрагмент ${i + 1} из ${frag.size}:\n$part")
                onPrompt(prompt)
                notes += PromptBuilder.clean(generate.generate(prompt, NOTES_MAX_TOKENS) { onPiece() })
            }
            val perNote = budget / frag.size.coerceAtLeast(1)
            material = notes.mapIndexed { i, n -> "Часть ${i + 1}:\n" + n.take(perNote) }.joinToString("\n\n")
        }
        status.onStatus("Составление протокола…")
        val prompt = PromptBuilder.chatml(system, "Расшифровка:\n$material")
        onPrompt(prompt)
        val sb = StringBuilder()
        generate.generate(prompt, PROTOCOL_MAX_TOKENS) { piece ->
            onPiece()
            sb.append(piece)
            onText(PromptBuilder.clean(sb.toString()))
        }
        return PromptBuilder.clean(sb.toString())
    }

    companion object {
        /** Протокол одного фрагмента в режиме chunk_merge (эталон llm-bench: `FRAG_MAX`). */
        const val FRAGMENT_MAX_TOKENS = 450
        /** Длинная запись делится на focused-фрагменты; короткие диалоги остаются одним запросом. */
        const val FRAGMENT_BUDGET_CHARS = 2500
        const val PROTOCOL_MAX_TOKENS = 700
        const val NOTES_MAX_TOKENS = 320
    }
}
