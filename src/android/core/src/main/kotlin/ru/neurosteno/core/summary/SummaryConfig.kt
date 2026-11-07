package ru.neurosteno.core.summary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Блок `summary` из `config/tiers/<tier>.json` (B50). Значения по умолчанию = рекомендация medium из `bench_rag`. */
@Serializable
data class SummaryConfig(
    /** `llm` | `extractive` (без LLM, lite). */
    val mode: String = "llm",
    @SerialName("fragment_chars") val fragmentChars: Int = 1500,
    @SerialName("max_new_tokens") val maxNewTokens: Int = 700,
    @SerialName("n_ctx") val nCtx: Int = 4096,
    @SerialName("min_support_point") val minSupportPoint: Double = 0.3,
    @SerialName("min_support_item") val minSupportItem: Double = 0.5,
    @SerialName("merge_title_sim") val mergeTitleSim: Double = 0.45,
    val segmenter: SegmenterConfig = SegmenterConfig(),
    /** 1-2 предложения «о чём» отдельным вызовом LLM (~4 с на ПК). */
    val overview: Boolean = true,
    /** Запускать выжимку автоматически в фоне после обработки. */
    val auto: Boolean = false,
    /** Сразу показать extractive-выжимку, а LLM-версию дописывать в фоне. */
    @SerialName("show_extractive_first") val showExtractiveFirst: Boolean = true,
) {
    val usesLlm: Boolean get() = mode.equals("llm", ignoreCase = true)
}

@Serializable
data class SegmenterConfig(
    val k: Int = 3,
    val alpha: Double = 0.0,
    @SerialName("min_utts") val minUtts: Int = 3,
    @SerialName("min_chars") val minChars: Int = 300,
) {
    companion object {
        /** Окно для лексических векторов (без эмбеддера): F1 границ 0.41 при k = 4 против 0.34 при k = 3 (`bench_rag/README`). */
        const val LEXICAL_K = 4
    }
}
