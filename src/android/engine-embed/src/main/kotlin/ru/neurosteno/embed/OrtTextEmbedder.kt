package ru.neurosteno.embed

import java.io.File
import kotlin.math.sqrt
import ru.neurosteno.core.search.EmbedderConfig
import ru.neurosteno.core.search.TextEmbedder
import ru.neurosteno.core.search.tokenizer.TextTokenizer

/**
 * Эмбеддер на ONNX Runtime (CPU, int8): USER2-small (mean-пулинг, префиксы `search_query: `/`search_document: `) для medium/hard и
 * rubert-tiny-turbo (CLS-пулинг, без префиксов) для lite. Токенайзер -- свой порт ([TextTokenizer], бит в бит с Python `tokenizers`).
 * Порядок как в эталоне `bench_rag/embed.py`: префикс + текст -> токены (усечение [EmbedderConfig.maxLen]) -> батчи по длине ->
 * `input_ids`/`attention_mask` (+ `token_type_ids`, если модель их ждёт) -> первый выход `[b, n, dim]` -> пулинг -> L2-нормировка.
 * Один экземпляр -- одна загруженная модель; [close] освобождает память (эмбеддер, LLM и ASR не бывают в памяти одновременно).
 */
class OrtTextEmbedder(modelFile: File, tokenizerFile: File, private val cfg: EmbedderConfig, private val batchSize: Int = BATCH) : TextEmbedder, AutoCloseable {
    private val handle: Long
    private val tokenizer: TextTokenizer = tokenizerFile.inputStream().use { TextTokenizer.load(it) }
    private val wantsTypeIds: Boolean
    override val dim: Int = cfg.dim
    private var out = FloatArray(0)

    init {
        require(cfg.pooling == "mean" || cfg.pooling == "cls") { "pooling ${cfg.pooling} не поддерживается" }
        OrtNative.ensureLoaded()
        handle = OrtNative.create(modelFile.absolutePath, cfg.threads.coerceAtLeast(1))
        wantsTypeIds = "token_type_ids" in OrtNative.inputNames(handle)
    }

    @Synchronized
    override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val prefix = if (isQuery) cfg.queryPrefix else cfg.docPrefix
        val ids = texts.map { tokenizer.encode(prefix + it, cfg.maxLen) }
        val order = ids.indices.sortedBy { ids[it].size }
        val res = arrayOfNulls<FloatArray>(texts.size)
        for (batch in order.chunked(batchSize)) {
            val n = batch.maxOf { ids[it].size }
            val b = batch.size
            val inputIds = LongArray(b * n) { tokenizer.padId.toLong() }
            val mask = LongArray(b * n)
            batch.forEachIndexed { r, i ->
                val t = ids[i]
                for (c in t.indices) { inputIds[r * n + c] = t[c].toLong(); mask[r * n + c] = 1L }
            }
            if (out.size < b * n * dim) out = FloatArray(b * n * dim)
            val got = OrtNative.run(handle, inputIds, mask, if (wantsTypeIds) LongArray(b * n) else null, b, n, out)
            check(got == b * n * dim) { "размерность выхода модели ${got / (b * n)} != конфиг $dim" }
            batch.forEachIndexed { r, i ->
                val v = FloatArray(dim)
                if (cfg.pooling == "cls") {
                    System.arraycopy(out, r * n * dim, v, 0, dim)
                } else {
                    var cnt = 0
                    for (c in 0 until n) {
                        if (mask[r * n + c] == 0L) continue
                        cnt++
                        val base = (r * n + c) * dim
                        for (j in 0 until dim) v[j] += out[base + j]
                    }
                    val d = maxOf(cnt, 1).toFloat()
                    for (j in 0 until dim) v[j] /= d
                }
                var s = 0.0
                for (x in v) s += x.toDouble() * x
                val nrm = maxOf(sqrt(s).toFloat(), 1e-9f)
                for (j in 0 until dim) v[j] /= nrm
                res[i] = v
            }
        }
        return res.map { it!! }
    }

    @Synchronized
    override fun close() = OrtNative.destroy(handle)

    companion object {
        const val BATCH = 8

        /**
         * Создаёт эмбеддер по конфигу из каталога набора моделей [modelsDir]; null -- файлов нет или модель не загрузилась
         * (поиск тогда работает только по BM25).
         */
        fun createOrNull(modelsDir: File, cfg: EmbedderConfig, onError: (Throwable) -> Unit = {}): OrtTextEmbedder? {
            val model = cfg.model?.let { File(modelsDir, it) }?.takeIf { it.isFile } ?: return null
            val tok = cfg.tokenizer?.let { File(modelsDir, it) }?.takeIf { it.isFile } ?: return null
            return try {
                OrtTextEmbedder(model, tok, cfg)
            } catch (e: Throwable) {
                onError(e)
                null
            }
        }
    }
}
