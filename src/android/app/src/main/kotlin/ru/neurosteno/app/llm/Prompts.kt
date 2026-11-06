package ru.neurosteno.app.llm

import android.content.res.AssetManager

/**
 * Системные промпты из `assets/prompts/`. Файлы можно заменить без правки кода (их готовит llm-bench); если файла нет,
 * используется встроенный запасной текст.
 */
class Prompts(private val assets: AssetManager) {
    val protocolSystem: String get() = read("protocol_system.txt") ?: DEFAULT_PROTOCOL
    val qaSystem: String get() = read("qa_system.txt") ?: DEFAULT_QA
    /** Конспект одного фрагмента длинной записи (первый этап протокола по частям). */
    val notesSystem: String get() = read("protocol_notes_system.txt") ?: DEFAULT_NOTES

    private fun read(name: String): String? =
        runCatching { assets.open("prompts/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val DEFAULT_PROTOCOL = "Ты секретарь совещания. По расшифровке составь протокол заседания на русском языке: " +
            "краткое резюме, обсуждавшиеся вопросы, принятые решения, поручения (кто, что, срок, если названы). " +
            "Используй только факты из расшифровки, ничего не выдумывай. Оформи списками, без вступлений."
        const val DEFAULT_QA = "Ты помощник, отвечающий на вопросы по расшифровке разговора. Отвечай по-русски, кратко и только по тексту " +
            "расшифровки; если ответа в ней нет, так и скажи. Называй говорящих по именам из расшифровки."
        const val DEFAULT_NOTES = "Ты секретарь совещания. Кратко (5–8 пунктов) перечисли главные темы, решения и поручения " +
            "из фрагмента расшифровки. Только факты из текста, без вступлений."
    }
}
