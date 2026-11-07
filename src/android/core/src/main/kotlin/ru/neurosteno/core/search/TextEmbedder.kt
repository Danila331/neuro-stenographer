package ru.neurosteno.core.search

/**
 * Эмбеддер текста для поиска и выжимки (B50/B51). Реализация на ONNX Runtime (USER2-small / rubert-tiny-turbo) -- в отдельном модуле;
 * `:core` зависит только от этого интерфейса.
 *
 * Контракт: векторы L2-нормированы; `isQuery = true` -- запрос (префикс `search_query: ` и т. п. добавляет сама реализация),
 * `false` -- документ (реплика или окно). Порядок и число векторов = порядок и число [texts]; длина вектора = [dim].
 */
interface TextEmbedder {
    val dim: Int
    fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>
}
