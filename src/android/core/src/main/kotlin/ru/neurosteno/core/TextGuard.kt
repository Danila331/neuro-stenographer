package ru.neurosteno.core

/**
 * Guard неизменности слов для Punctuator/LLM (ARCHITECTURE §6).
 *
 * `norm(s)` = lower -> `ё -> е` -> удалить всё, кроме букв/цифр/пробелов -> схлопнуть пробелы.
 * Если `norm(output).split() != norm(input).split()` -- берём input (+ заглавная первая буква и точка).
 * Слова никогда не теряются и не добавляются.
 */
object TextGuard {

    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val c = if (ch == 'ё') 'е' else ch
            if (c.isLetterOrDigit() || c.isWhitespace()) sb.append(c)
        }
        return sb.toString().trim().replace(WHITESPACE, " ")
    }

    fun normWords(s: String): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    fun sameWords(a: String, b: String): Boolean = normWords(a) == normWords(b)

    /** Заглавная первая буква + точка в конце (если нет завершающего знака). */
    fun finalize(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        val cap = capitalizeFirst(t)
        return if (cap.last() in TERMINAL) cap else "$cap."
    }

    /** Возвращает [output], если он не меняет слов [input]; иначе безопасный fallback. */
    fun guardText(input: String, output: String): String {
        val out = output.trim()
        return if (out.isNotEmpty() && sameWords(input, out)) out else finalize(input)
    }

    /** Применяет guard к реплике: слова/времена/спикер берутся ИЗ input, от output принимается только текст. */
    fun guardUtterance(input: Utterance, output: Utterance?): Utterance {
        val text = guardText(input.text, output?.text ?: "")
        return input.copy(text = text)
    }

    /** Как в эталоне: заглавной становится первый СИМВОЛ строки (если он не буква, остаётся как есть). */
    private fun capitalizeFirst(s: String): String = s.substring(0, 1).uppercase() + s.substring(1)

    private val WHITESPACE = Regex("\\s+")
    private val TERMINAL = setOf('.', '!', '?', '…')
}

/** Нормализация текста по секции `normalization` конфига варианта (по умолчанию -- без изменений). */
object TextNormalizer {
    fun apply(text: String, cfg: NormalizationConfig): String {
        var t = text
        if (cfg.yoToE) t = t.replace('ё', 'е').replace('Ё', 'Е')
        if (cfg.case == "lower") t = t.lowercase()
        return t // cfg.numbers: пока поддерживается только as_is
    }
}

/**
 * Обёртка над любым [Punctuator]: гарантирует неизменность слов и таймстемпов и не даёт упасть пайплайну.
 * Любая ошибка/несовпадение числа реплик -> fallback ([TextGuard.finalize]); отмена пробрасывается.
 */
class GuardedPunctuator(private val inner: Punctuator?) : Punctuator {
    override fun punctuate(utterances: List<Utterance>, progress: ProgressListener): List<Utterance> {
        val produced: List<Utterance>? = try {
            inner?.punctuate(utterances, progress)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (inner == null) return utterances
        val usable = produced?.takeIf { it.size == utterances.size }
        return utterances.mapIndexed { i, u -> TextGuard.guardUtterance(u, usable?.get(i)) }
    }

    override fun close() {
        inner?.close()
    }
}
