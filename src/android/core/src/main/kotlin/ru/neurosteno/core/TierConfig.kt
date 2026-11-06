package ru.neurosteno.core

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Конфиг варианта сборки (`config/tiers/<tier>.json`, ARCHITECTURE §3).
 * Тот же файл читает Python-эталон. Неизвестные поля игнорируются, отсутствующие берут дефолт.
 * Пути моделей -- относительно корня моделей (Android: `filesDir/models`, ПК: `.cache/models`).
 */
@Serializable
data class TierConfig(
    val tier: String = "medium",
    @SerialName("num_threads") val numThreads: Int = 4,
    val audio: AudioConfig = AudioConfig(),
    val vad: VadConfig = VadConfig(),
    val asr: AsrConfig = AsrConfig(),
    val diarization: DiarizationConfig = DiarizationConfig(),
    val align: AlignConfig = AlignConfig(),
    val punctuation: PunctuationConfig = PunctuationConfig(),
    val llm: LlmConfig = LlmConfig(),
    val normalization: NormalizationConfig = NormalizationConfig(),
) {
    /** Все модели, нужные этому варианту (для проверки наличия assets и для stage_models). */
    fun requiredModelPaths(): List<String> = buildList {
        add(vad.model)
        if (audio.denoise.enabled) audio.denoise.model?.let(::add)
        add(asr.modelDir)
        asr.secondPass?.modelDir?.let(::add)
        add(diarization.segmentation)
        add(diarization.embedding)
        punctuation.model?.let(::add)
        llm.model?.let(::add)
    }.distinct()

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        /** Разбор JSON. Бросает [IllegalArgumentException] с понятным сообщением при битом JSON. */
        fun parse(text: String): TierConfig = try {
            json.decodeFromString(serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Некорректный конфиг варианта: ${e.message}", e)
        }

        /** SHA-256 исходного текста (первые 12 hex) -- `config_hash` в meta прогонов. */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(12)
    }
}

@Serializable
data class AudioConfig(
    @SerialName("sample_rate") val sampleRate: Int = 16_000,
    val denoise: DenoiseConfig = DenoiseConfig(),
)

@Serializable
data class DenoiseConfig(
    val enabled: Boolean = false,
    val model: String? = null,
    @SerialName("snr_threshold_db") val snrThresholdDb: Double = 12.0,
)

@Serializable
data class VadConfig(
    val model: String = "silero-vad/silero_vad.onnx",
    val threshold: Double = 0.45,
    @SerialName("min_silence_ms") val minSilenceMs: Int = 300,
    @SerialName("min_speech_ms") val minSpeechMs: Int = 120,
    @SerialName("pad_ms") val padMs: Int = 150,
    @SerialName("max_speech_s") val maxSpeechS: Double = 25.0,
)

@Serializable
data class AsrConfig(
    val engine: String = "gigaam_rnnt",
    @SerialName("model_dir") val modelDir: String = "asr/gigaam-v3-rnnt-int8",
    val decoding: String = "greedy_search",
    @SerialName("max_active_paths") val maxActivePaths: Int = 4,
    @SerialName("hotwords_file") val hotwordsFile: String? = null,
    @SerialName("hotwords_score") val hotwordsScore: Double = 1.5,
    val chunk: ChunkConfig = ChunkConfig(),
    @SerialName("second_pass") val secondPass: SecondPassConfig? = null,
)

@Serializable
data class ChunkConfig(
    @SerialName("max_s") val maxS: Double = 25.0,
    @SerialName("min_s") val minS: Double = 1.0,
    @SerialName("overlap_ms") val overlapMs: Int = 0,
)

@Serializable
data class SecondPassConfig(
    val engine: String = "whisper",
    @SerialName("model_dir") val modelDir: String,
    val trigger: String = "low_confidence",
    @SerialName("confidence_threshold") val confidenceThreshold: Double = 0.6,
)

@Serializable
data class DiarizationConfig(
    val segmentation: String = "diar/pyannote-segmentation-3.0/model.int8.onnx",
    val embedding: String = "diar/embedding/model.onnx",
    val clustering: ClusteringConfig = ClusteringConfig(),
    @SerialName("min_duration_on_s") val minDurationOnS: Double = 0.2,
    @SerialName("min_duration_off_s") val minDurationOffS: Double = 0.3,
)

@Serializable
data class ClusteringConfig(
    val method: String = "fast_clustering",
    val threshold: Double = 0.6,
    @SerialName("num_speakers") val numSpeakers: Int? = null,
    @SerialName("max_speakers") val maxSpeakers: Int = 8,
)

@Serializable
data class AlignConfig(
    @SerialName("max_gap_ms") val maxGapMs: Long = 1500,
    @SerialName("min_island_words") val minIslandWords: Int = 1,
    @SerialName("min_island_ms") val minIslandMs: Long = 400,
)

@Serializable
data class PunctuationConfig(
    /** asr_native | punct_model | llm */
    val source: String = "llm",
    val model: String? = null,
)

@Serializable
data class LlmConfig(
    val model: String? = null,
    @SerialName("n_ctx") val nCtx: Int = 2048,
    @SerialName("n_threads") val nThreads: Int = 4,
    @SerialName("gpu_layers") val gpuLayers: Int = 0,
    val punctuation: Boolean = true,
    @SerialName("speaker_fix") val speakerFix: Boolean = false,
    val summary: Boolean = true,
    @SerialName("time_budget_rtf") val timeBudgetRtf: Double = 0.15,
)

@Serializable
data class NormalizationConfig(
    val numbers: String = "as_is",
    @SerialName("yo_to_e") val yoToE: Boolean = false,
    /** as_is | lower */
    val case: String = "as_is",
)
