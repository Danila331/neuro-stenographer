package ru.neurosteno.core.live

import ru.neurosteno.core.audio.Pcm

/**
 * Накопитель живого текста поверх [StreamingAsr]: превращает обновления движка в [LiveState]. Чистая логика без
 * Android и потоков (поток и очередь -- в приложении), поэтому тестируется на фейке.
 *
 * Правила: пустые фразы игнорируются; фраза нормализуется (лишние пробелы; регистр не трогаем -- границы фраз у потоковой модели
 * могут попасть и внутрь слова, заглавная в середине выглядела бы ошибкой; заглавную первую букву всего текста ставит экран);
 * хранится не более [keepPhrases] последних фраз.
 */
class LivePreview(private val asr: StreamingAsr, private val keepPhrases: Int = 200) : AutoCloseable {
    private val finals = ArrayList<String>()
    private var partial = ""
    private var dropped = 0
    private var closed = false

    val state: LiveState get() = LiveState(finals.toList(), partial, dropped)

    /** PCM16 с микрофона (первые [length] отсчётов) -> float -> движок. Возвращает новое состояние, если оно изменилось. */
    fun feed(pcm: ShortArray, length: Int): LiveState? {
        if (closed || length <= 0) return null
        return apply(asr.accept(Pcm.shortsToMono(pcm, length, 1)))
    }

    fun feed(samples: FloatArray): LiveState? = if (closed || samples.isEmpty()) null else apply(asr.accept(samples))

    /** Конец записи: хвост становится финальной фразой, незавершённая гипотеза очищается. */
    fun finish(): LiveState {
        if (!closed) {
            apply(asr.finish())
            if (partial.isNotBlank()) addFinal(partial)
            partial = ""
        }
        return state
    }

    private fun apply(u: LiveUpdate?): LiveState? {
        if (u == null) return null
        var changed = false
        u.finalPhrase?.let { if (addFinal(it)) changed = true }
        val p = tidy(u.partial)
        if (p != partial) { partial = p; changed = true }
        return if (changed) state else null
    }

    private fun addFinal(text: String): Boolean {
        val t = tidy(text)
        if (t.isBlank()) return false
        finals += t
        while (finals.size > keepPhrases) { finals.removeAt(0); dropped++ }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        asr.close()
    }

    companion object {
        /** Сжимает пробелы; пустая строка остаётся пустой. */
        fun tidy(text: String): String = text.trim().replace(Regex("\\s+"), " ")
    }
}
