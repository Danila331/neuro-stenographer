package ru.neurosteno.core

/**
 * Сглаживание языка по VAD-чанкам (`language.per_chunk`): бинарно «en -> английская ASR, иначе русская».
 * Гистерезис: начальное состояние -- ru; состояние меняется на предсказание чанка, только если следующий чанк с
 * предсказанием его подтверждает либо сам чанк длинный ([confidentMs]); одиночный «выброс» между чанками одного языка
 * игнорируется. Чанки без предсказания (слишком короткие для LID) наследуют текущее состояние.
 */
object LanguageSmoother {
    const val MIN_LID_MS: Long = 1_500
    const val CONFIDENT_MS: Long = 6_000

    /** @param predictions "en" | другой код | null (нет предсказания); @param durationsMs длительности чанков. */
    fun smooth(predictions: List<String?>, durationsMs: List<Long>, confidentMs: Long = CONFIDENT_MS): List<String> {
        require(predictions.size == durationsMs.size)
        val bin = predictions.map { p -> p?.let { if (it.lowercase().startsWith(LanguageConfig.EN)) LanguageConfig.EN else LanguageConfig.RU } }
        var state = LanguageConfig.RU
        val out = ArrayList<String>(bin.size)
        for (i in bin.indices) {
            val p = bin[i]
            if (p != null && p != state) {
                val next = (i + 1 until bin.size).firstNotNullOfOrNull { bin[it] }
                if (next == p || durationsMs[i] >= confidentMs) state = p
            }
            out += state
        }
        return out
    }
}
