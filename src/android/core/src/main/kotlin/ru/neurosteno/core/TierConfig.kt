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
    /** Английская ASR (та же структура, что `asr`); null -> английский не поддерживается в этом варианте. */
    @SerialName("asr_en") val asrEn: AsrConfig? = null,
    val language: LanguageConfig = LanguageConfig(),
    val diarization: DiarizationConfig = DiarizationConfig(),
    val align: AlignConfig = AlignConfig(),
    val punctuation: PunctuationConfig = PunctuationConfig(),
    val llm: LlmConfig = LlmConfig(),
    val normalization: NormalizationConfig = NormalizationConfig(),
    val postprocess: PostprocessConfig = PostprocessConfig(),
) {
    /** Все модели, нужные этому варианту (для проверки наличия assets и для stage_models). */
    fun requiredModelPaths(): List<String> = buildList {
        add(vad.model)
        if (audio.denoise.enabled) audio.denoise.model?.let(::add)
        add(asr.modelDir)
        asr.secondPass?.modelDir?.let(::add)
        asrEn?.let { en ->
            add(en.modelDir)
            en.secondPass?.modelDir?.let(::add)
        }
        if (language.effectiveMode == LanguageConfig.AUTO) language.lidModel?.let(::add)
        add(diarization.segmentation)
        add(diarization.embedding)
        punctuation.model?.let(::add)
        llm.model?.let(::add)
    }.distinct()

    /**
     * Без этих моделей обработка невозможна (VAD, основная ASR, диаризация). Остальное (английская ASR, LID, денойз,
     * LLM, второй проход) необязательно: если файлов нет, соответствующая возможность отключается с предупреждением.
     */
    fun essentialModelPaths(): List<String> =
        listOf(vad.model, asr.modelDir, diarization.segmentation, diarization.embedding).distinct()

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
data class LanguageConfig(
    /** auto | ru | en. Нет секции / неизвестное значение -> ru (как раньше). */
    val mode: String = RU,
    @SerialName("lid_model") val lidModel: String? = null,
    /** Сколько секунд речи отдавать определителю языка. */
    @SerialName("lid_seconds") val lidSeconds: Int = 10,
    /** LID на каждом VAD-чанке с гистерезисом (смешанные ru/en диалоги); false -- один язык на файл. */
    @SerialName("per_chunk") val perChunk: Boolean = false,
) {
    val effectiveMode: String
        get() = when (mode.lowercase()) {
            AUTO -> AUTO
            EN -> EN
            else -> RU
        }

    companion object {
        const val AUTO = "auto"
        const val RU = "ru"
        const val EN = "en"
    }
}

@Serializable
data class AudioConfig(
    @SerialName("sample_rate") val sampleRate: Int = 16_000,
    val denoise: DenoiseConfig = DenoiseConfig(),
    /** Пиковая нормализация громкости перед VAD/ASR (BACKLOG B24); по умолчанию выключена. */
    @SerialName("normalize_gain") val normalizeGain: NormalizeGainConfig = NormalizeGainConfig(),
)

@Serializable
data class NormalizeGainConfig(
    /** Выключено по умолчанию; включается только явно (BACKLOG B24: пока не доказано выигрыша вместе с порогом VAD). */
    val enabled: Boolean = false,
    /** peak (другие значения трактуются как «выключено»). */
    val mode: String = "peak",
    /** Целевой пик, dBFS. Старый ключ `target_peak_dbfs` тоже читается. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.json.JsonNames("target_peak_dbfs")
    @SerialName("peak_dbfs") val peakDbfs: Double = -1.0,
    /** Максимальное усиление, дБ. */
    @SerialName("max_gain_db") val maxGainDb: Double = 40.0,
    /** Максимальное ослабление, дБ (слишком громкие записи ослабляются не сильнее). */
    @SerialName("max_attenuation_db") val maxAttenuationDb: Double = 6.0,
    /** Почти нулевой сигнал (пик ниже порога, dBFS) не трогаем: усиливать нечего. */
    @SerialName("skip_if_peak_below_dbfs") val skipIfPeakBelowDbfs: Double = -85.0,
    /** Нормализовать только файлы с пиком ниже этого уровня (dBFS); null -- всегда. */
    @SerialName("apply_if_peak_below_dbfs") val applyIfPeakBelowDbfs: Double? = null,
    /** all -- каждая стадия видит нормализованный сигнал; asr -- VAD/rescue/диаризация читают ИСХОДНЫЙ, ASR и LID -- копию;
     *  asr_chunk -- каждый чанк ASR нормализуется по своему пику. */
    val scope: String = "all",
) {
    val effectiveScope: String get() = when (scope.lowercase()) { "asr" -> "asr"; "asr_chunk" -> "asr_chunk"; else -> "all" }
    val active: Boolean get() = enabled && mode.lowercase() == "peak"
}

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
    /** Энергетический «спасатель» речи, которую Silero не увидел (тихие/кодированные голоса); по умолчанию выключен. */
    val rescue: VadRescueConfig = VadRescueConfig(),
    /** pack -- упаковка как в bench_asr/refpipe (сегменты не склеиваются, резка по минимуму энергии); legacy -- прежний ChunkPlanner. */
    val chunking: String = "pack",
) {
    val packChunking: Boolean get() = chunking.lowercase() != "legacy"
}

@Serializable
data class VadRescueConfig(
    val enabled: Boolean = false,
    @SerialName("snr_db") val snrDb: Double = 12.0,
    @SerialName("abs_floor_db") val absFloorDb: Double = -55.0,
    @SerialName("bridge_ms") val bridgeMs: Int = 300,
    @SerialName("min_ms") val minMs: Int = 600,
    @SerialName("margin_ms") val marginMs: Int = 200,
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
    /** Размер батча чанков (используется только Python-эталоном; на Android чанки декодируются по одному). */
    @SerialName("batch_size") val batchSize: Int = 1,
    /** Конец слова = старт последнего токена + tail (мс), не позже начала следующего слова. */
    @SerialName("word_tail_ms") val wordTailMs: Int = 40,
    /** Сдвиг всех временных меток токенов (калибровка по размеченным данным). */
    @SerialName("word_start_offset_ms") val wordStartOffsetMs: Int = 0,
    val language: String = "ru",
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
    /** false -> диаризация не запускается, все слова одного говорящего. */
    val enabled: Boolean = true,
    @SerialName("window_shift_ratio") val windowShiftRatio: Double = 0.1,
    /** null -> `num_threads` верхнего уровня. */
    @SerialName("num_threads") val numThreads: Int? = null,
    /** «Говорящие по ASR-фразам» вместо кластеризации sherpa (BACKLOG B46); по умолчанию выключено. */
    val relabel: RelabelConfig = RelabelConfig(),
)

@Serializable
data class RelabelConfig(
    val enabled: Boolean = false,
    @SerialName("gap_ms") val gapMs: Int = 300,
    @SerialName("max_unit_ms") val maxUnitMs: Int = 12_000,
    @SerialName("split_min_side_ms") val splitMinSideMs: Int = 300,
    @SerialName("pad_ms") val padMs: Int = 40,
    @SerialName("embed_max_s") val embedMaxS: Double = 10.0,
    @SerialName("min_reliable_ms") val minReliableMs: Int = 800,
    @SerialName("k_min") val kMin: Int = 2,
    @SerialName("k_max") val kMax: Int = 6,
    @SerialName("max_cluster_units") val maxClusterUnits: Int = 400,
    val refine: Int = 1,
    /** Защита от монологов: если лучший силуэт при k >= 2 ниже порога -- один говорящий; null -- без защиты. */
    @SerialName("k1_silhouette_thr") val k1SilhouetteThr: Double? = null,
    /** Защита срабатывает, только если штатная диаризация тоже видит одного говорящего. */
    @SerialName("k1_require_stock_single") val k1RequireStockSingle: Boolean = false,
    /** «Видит» = говорящие с долей >= этой от суммарной речи штатной диаризации. */
    @SerialName("k1_stock_min_share") val k1StockMinShare: Double = 0.10,
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

@Serializable
data class PostprocessConfig(
    /** as_is | words ("25" -> "двадцать пять"). */
    val numbers: String = "as_is",
    @SerialName("drop_tiny_chunks") val dropTinyChunks: DropTinyChunksConfig = DropTinyChunksConfig(),
    /** off | after_terminal | all -- см. [ru.neurosteno.core.postprocess.Postprocess.stripDashes]. */
    @SerialName("strip_dashes") val stripDashes: String = "off",
) {
    val spellNumbers: Boolean get() = numbers.lowercase() == "words"
}

@Serializable
data class DropTinyChunksConfig(
    val enabled: Boolean = false,
    /** Чанк, у которого весь вывод не длиннее стольких букв/цифр, отбрасывается (галлюцинации «а/э/няу»). */
    @SerialName("max_chars") val maxChars: Int = 2,
)
