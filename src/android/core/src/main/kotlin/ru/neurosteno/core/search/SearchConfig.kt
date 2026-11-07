package ru.neurosteno.core.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Блок `search` из `config/tiers/<tier>.json` (B51). Значения по умолчанию = рекомендация medium из `bench_rag`. */
@Serializable
data class SearchConfig(
    val enabled: Boolean = true,
    val embedder: EmbedderConfig = EmbedderConfig(),
    /** Режим нарезки: `utt` | `turn` | `win<W>[s<S>]`. */
    val chunk: String = Chunker.DEFAULT_MODE,
    val bm25: Bm25Config = Bm25Config(),
    val fusion: FusionConfig = FusionConfig(),
    /** `soft` (бонус к оценке) | `hard` (фильтр) | `off`. */
    @SerialName("speaker_filter") val speakerFilter: String = "soft",
    @SerialName("top_k") val topK: Int = 5,
    val store: StoreConfig = StoreConfig(),
    val answer: AnswerConfig = AnswerConfig(),
)

@Serializable
data class EmbedderConfig(
    /** Относительный путь ONNX-модели внутри набора моделей (`embed/user2-small/model.int8.onnx`). */
    val model: String? = null,
    val tokenizer: String? = null,
    val dim: Int = 384,
    /** `mean` | `cls`. */
    val pooling: String = "mean",
    @SerialName("query_prefix") val queryPrefix: String = "",
    @SerialName("doc_prefix") val docPrefix: String = "",
    @SerialName("max_len") val maxLen: Int = 256,
    val threads: Int = 2,
)

@Serializable
data class Bm25Config(val k1: Double = 1.2, val b: Double = 0.75, val stemmer: String = "snow5")

@Serializable
data class FusionConfig(
    val method: String = "rrf",
    val k: Int = 60,
    @SerialName("dense_weight") val denseWeight: Double = 0.5,
)

@Serializable
data class StoreConfig(
    /** `bruteforce` | `objectbox`. */
    val kind: String = "bruteforce",
    /** Что использовать, если [kind] не открылся (например, ObjectBox не загрузился). */
    val fallback: String = "bruteforce",
    val metric: String = "cosine",
)

@Serializable
data class AnswerConfig(
    val enabled: Boolean = false,
    @SerialName("top_chunks") val topChunks: Int = 3,
    @SerialName("max_context_chars") val maxContextChars: Int = 3200,
    @SerialName("max_new_tokens") val maxNewTokens: Int = 160,
)
