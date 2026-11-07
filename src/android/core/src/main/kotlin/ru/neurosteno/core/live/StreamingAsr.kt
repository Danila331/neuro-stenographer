package ru.neurosteno.core.live

/**
 * Потоковое распознавание речи для превью во время записи (B13). Вход -- моно 16 кГц float [-1, 1); реализация
 * (`:engine-sherpa`, `SherpaStreamingAsr`) копит звук, декодирует готовые окна и отдаёт гипотезы. Не потокобезопасна:
 * все вызовы идут с одного рабочего потока ([LivePreview]). Хранит нативную модель -- обязательно [close] после записи.
 */
interface StreamingAsr : AutoCloseable {
    /** Принять очередную порцию звука; возвращает обновление или `null`, если ничего не изменилось. */
    fun accept(samples: FloatArray): LiveUpdate?

    /** Конец записи: дорасшифровать хвост и вернуть последнюю фразу (если есть). */
    fun finish(): LiveUpdate?
}

/**
 * Гипотеза движка: [partial] -- текущая незавершённая фраза (меняется), [finalPhrase] -- фраза, завершённая
 * по паузе (endpoint) в этом обновлении; после неё [partial] пуст.
 */
data class LiveUpdate(val partial: String = "", val finalPhrase: String? = null)

/** Что показывает экран записи. [finals] -- завершённые фразы, [partial] -- текущая гипотеза. */
data class LiveState(
    val finals: List<String> = emptyList(),
    val partial: String = "",
    /** Число фраз, свернутых из начала списка (лимит [LiveConfig.keepPhrases]). */
    val droppedPhrases: Int = 0,
) {
    val isEmpty: Boolean get() = finals.isEmpty() && partial.isBlank()

    /** Весь живой текст одной строкой, первая буква заглавная (экран и описание для доступности). */
    fun fullText(): String = (finals + partial).filter { it.isNotBlank() }.joinToString(" ").replaceFirstChar { it.uppercase() }
}
