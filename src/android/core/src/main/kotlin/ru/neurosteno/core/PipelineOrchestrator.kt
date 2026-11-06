package ru.neurosteno.core

import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

/** Параметры прогона, не связанные с выбором движков. */
data class PipelineSettings(
    val tier: String,
    val align: AlignConfig = AlignConfig(),
    val normalization: NormalizationConfig = NormalizationConfig(),
    val denoiseEnabled: Boolean = false,
    val snrThresholdDb: Double = 12.0,
    /** null -> автоопределение числа говорящих. */
    val numSpeakers: Int? = null,
    val appVersion: String = "dev",
    /** Роль -> идентификатор модели (для расширенного экспорта/отладки). */
    val models: Map<String, String> = emptyMap(),
    /** auto | ru | en (см. [LanguageConfig]). */
    val languageMode: String = LanguageConfig.RU,
    /** Языки, для которых в этом варианте есть ASR. */
    val supportedLanguages: Set<String> = setOf(LanguageConfig.RU),
    /** План ресурсов (память/потоки); null -> без ограничений. */
    val plan: ResourcePlan? = null,
) {
    companion object {
        fun from(cfg: TierConfig, appVersion: String, plan: ResourcePlan? = null): PipelineSettings = PipelineSettings(
            tier = cfg.tier,
            align = cfg.align,
            normalization = cfg.normalization,
            denoiseEnabled = cfg.audio.denoise.enabled && (plan?.denoise ?: true),
            snrThresholdDb = cfg.audio.denoise.snrThresholdDb,
            numSpeakers = cfg.diarization.clustering.numSpeakers,
            appVersion = appVersion,
            models = buildMap {
                put("vad", cfg.vad.model)
                put("asr", cfg.asr.modelDir)
                cfg.asrEn?.let { put("asr_en", it.modelDir) }
                cfg.language.lidModel?.let { put("lid", it) }
                put("diarization_segmentation", cfg.diarization.segmentation)
                put("diarization_embedding", cfg.diarization.embedding)
                cfg.llm.model?.let { put("llm", it) }
            },
            languageMode = cfg.language.effectiveMode,
            supportedLanguages = if (cfg.asrEn != null) setOf(LanguageConfig.RU, LanguageConfig.EN) else setOf(LanguageConfig.RU),
            plan = plan,
        )
    }
}

/**
 * Фабрики движков. Оркестратор САМ создаёт каждый движок непосредственно перед стадией и закрывает
 * сразу после неё (модели в памяти по очереди): ASR и диаризация выгружаются до создания LLM-пунктуатора.
 * Фабрика может бросить исключение -- оно обрабатывается так же, как сбой самой стадии.
 */
class EngineProviders(
    val vad: () -> Vad,
    /** Аргумент -- язык ("ru" | "en"). */
    val asr: (language: String) -> AsrEngine,
    val diarizer: () -> Diarizer,
    val punctuator: (() -> Punctuator)? = null,
    val denoiser: (() -> Denoiser)? = null,
    val languageId: (() -> LanguageId)? = null,
)

fun interface PipelineProgressListener {
    /** [stageFraction] -- прогресс текущей стадии 0..1; [overall] -- общий прогресс 0..1 (монотонный). */
    fun onProgress(stage: Stage, stageFraction: Float, overall: Float)
}

/** Невосстановимая ошибка стадии (decode/asr). Остальные стадии деградируют с предупреждением. */
class PipelineException(val stage: Stage, message: String, cause: Throwable?) : Exception(message, cause)

data class PipelineResult(
    val transcript: Transcript,
    /** JSON строго по ТЗ (то, что уходит в экспорт). */
    val json: String,
    /** Нефатальные проблемы, обработанные деградацией (например, «диаризация не удалась -> один говорящий»). */
    val warnings: List<String>,
)

/**
 * Пайплайн: decode -> (denoise) -> vad -> (lid) -> [asr || diarization] -> align -> punctuation -> export.
 *
 * - Движки создаются и закрываются самим оркестратором ([EngineProviders]), по очереди (память).
 * - Отмена -- через корутины: движки получают [ProgressListener], который бросает [CancellationException],
 *   как только корутина отменена (движки обязаны вызывать его не реже раза в ~1 с); дополнительно
 *   блокирующие вызовы выполняются в `runInterruptible`.
 * - Деградация вместо падения: сбой denoise -> исходное аудио; VAD -> вся запись как речь; LID -> ru;
 *   диаризация -> один говорящий; пунктуация -> [TextGuard.finalize]. Сбой decode/ASR -> [PipelineException].
 * - Время каждой стадии (wall-clock, мс, вместе с загрузкой модели) пишется в [TranscriptMeta.stageMs];
 *   ASR и диаризация идут параллельно, их времена перекрываются, поэтому сумма стадий может превышать `total`.
 */
class PipelineOrchestrator(
    private val decoder: AudioDecoder,
    private val engines: EngineProviders,
    private val settings: PipelineSettings,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val aligner = Aligner(settings.align)

    suspend fun run(input: AudioInput, listener: PipelineProgressListener? = null): PipelineResult {
        val tracker = Tracker(listener)
        val warnings: MutableList<String> = Collections.synchronizedList(ArrayList())
        val stageMs = Collections.synchronizedMap(LinkedHashMap<String, Long>())
        val t0 = nanoTime()
        settings.plan?.reasons?.forEach { warnings += "Ресурсы: $it" }

        suspend fun <T> timed(stage: Stage, block: suspend () -> T): T {
            val s = nanoTime()
            tracker.report(stage, 0f)
            try {
                return block()
            } finally {
                stageMs[stage.key] = (nanoTime() - s) / 1_000_000
                tracker.report(stage, 1f)
            }
        }

        // ---- decode ----
        var audio = timed(Stage.DECODE) {
            fatal(Stage.DECODE) { blocking(tracker, Stage.DECODE) { decoder.decode(input, it) } }
        }
        val durationMs = audio.durationMs
        if (audio.samples.isEmpty() || durationMs <= 0) {
            warnings += "Пустое аудио"
            return finish(emptyList(), durationMs, LanguageConfig.RU, t0, stageMs, warnings, tracker)
        }

        // ---- denoise (только при низком SNR) ----
        val dnFactory = engines.denoiser
        if (settings.denoiseEnabled && dnFactory != null && SnrEstimator.estimateDb(audio) < settings.snrThresholdDb) {
            val source = audio
            audio = timed(Stage.DENOISE) {
                soft(Stage.DENOISE, warnings, source) {
                    blocking(tracker, Stage.DENOISE) { l -> dnFactory().use { it.denoise(source, l) } }
                }
            }
        } else {
            tracker.report(Stage.DENOISE, 1f)
        }
        val processed = audio

        // ---- vad ----
        val speech: List<TimeSpan> = timed(Stage.VAD) {
            soft(Stage.VAD, warnings, listOf(TimeSpan(0, durationMs))) {
                sanitizeSpans(blocking(tracker, Stage.VAD) { engines.vad().use { it.detect(processed) } }, durationMs)
            }
        }
        if (speech.isEmpty()) {
            for (s in listOf(Stage.LID, Stage.ASR, Stage.DIARIZATION)) tracker.report(s, 1f)
            return finish(emptyList(), durationMs, LanguageConfig.RU, t0, stageMs, warnings, tracker)
        }

        // ---- language id ----
        val language = timed(Stage.LID) { chooseLanguage(processed, speech, tracker, warnings) }

        // ---- asr || diarization ----
        val (words, turns) = coroutineScope {
            val asrJob = async {
                timed(Stage.ASR) {
                    fatal(Stage.ASR) {
                        blocking(tracker, Stage.ASR) { l -> engines.asr(language).use { it.transcribe(processed, speech, l) } }
                    }
                }
            }
            val diarJob = async {
                timed(Stage.DIARIZATION) {
                    soft(Stage.DIARIZATION, warnings, emptyList()) {
                        blocking(tracker, Stage.DIARIZATION) { l ->
                            engines.diarizer().use { it.diarize(processed, settings.numSpeakers, l) }
                        }
                    }
                }
            }
            asrJob.await() to diarJob.await()
        } // здесь ASR и диаризация уже закрыты -- память свободна под LLM

        // ---- align ----
        coroutineContext.ensureActive()
        val aligned = timed(Stage.ALIGN) { aligner.align(words, turns) }

        // ---- punctuation ----
        val punctuated = timed(Stage.PUNCTUATION) {
            val factory = engines.punctuator
            val allowed = settings.plan?.llmPunctuation ?: true
            if (factory != null && !allowed) warnings += "Пунктуация LLM отключена планом ресурсов"
            val punct: Punctuator? = if (factory != null && allowed) {
                soft<Punctuator?>(Stage.PUNCTUATION, warnings, null) { blocking(tracker, Stage.PUNCTUATION) { factory() } }
            } else {
                null
            }
            val result = if (factory != null && punct == null) {
                // пунктуатор предусмотрен, но недоступен (память/ошибка) -- хотя бы заглавная буква и точка
                aligned.map { it.copy(text = TextGuard.finalize(it.text)) }
            } else {
                try {
                    blocking(tracker, Stage.PUNCTUATION) { l -> GuardedPunctuator(punct).punctuate(aligned, l) }
                } finally {
                    runCatching { punct?.close() }
                }
            }
            result.map { it.copy(text = TextNormalizer.apply(it.text, settings.normalization)) }
        }

        return finish(punctuated, durationMs, language, t0, stageMs, warnings, tracker)
    }

    private suspend fun chooseLanguage(
        audio: AudioBuffer,
        speech: List<TimeSpan>,
        tracker: Tracker,
        warnings: MutableList<String>,
    ): String {
        val mode = settings.languageMode
        val requested = when (mode) {
            LanguageConfig.AUTO -> {
                val lid = engines.languageId
                if (lid == null) {
                    LanguageConfig.RU
                } else {
                    soft(Stage.LID, warnings, LanguageConfig.RU) {
                        val raw = blocking(tracker, Stage.LID) { lid().use { it.detect(audio, speech) } }
                        if (raw.trim().lowercase().startsWith(LanguageConfig.EN)) LanguageConfig.EN else LanguageConfig.RU
                    }
                }
            }
            LanguageConfig.EN -> LanguageConfig.EN
            else -> LanguageConfig.RU
        }
        if (requested !in settings.supportedLanguages) {
            warnings += "Язык '$requested' не поддерживается этим вариантом -- используется ru"
            return LanguageConfig.RU
        }
        return requested
    }

    private suspend fun finish(
        utterances: List<Utterance>,
        durationMs: Long,
        language: String,
        t0: Long,
        stageMs: MutableMap<String, Long>,
        warnings: List<String>,
        tracker: Tracker,
    ): PipelineResult {
        coroutineContext.ensureActive()
        tracker.report(Stage.EXPORT, 0f)
        val e0 = nanoTime()
        val finalUtterances = TranscriptJson.normalize(utterances, durationMs)
        val json = TranscriptJson.toJson(finalUtterances, durationMs)
        stageMs[Stage.EXPORT.key] = (nanoTime() - e0) / 1_000_000
        tracker.report(Stage.EXPORT, 1f)
        val total = (nanoTime() - t0) / 1_000_000
        val ordered = LinkedHashMap<String, Long>()
        Stage.entries.forEach { s -> stageMs[s.key]?.let { ordered[s.key] = it } }
        ordered["total"] = total
        val meta = TranscriptMeta(
            tier = settings.tier,
            audioDurationMs = durationMs,
            processingMs = total,
            stageMs = ordered,
            models = settings.models,
            appVersion = settings.appVersion,
            language = language,
        )
        return PipelineResult(Transcript(finalUtterances, meta), json, ArrayList(warnings))
    }

    // ---- вспомогательное ----

    /** Блокирующий вызов движка вне основного потока; отмена корутины -> CancellationException из прогресса + interrupt. */
    private suspend fun <T> blocking(tracker: Tracker, stage: Stage, block: (ProgressListener) -> T): T {
        val listener = tracker.listener(stage, coroutineContext.job)
        return runInterruptible(dispatcher) { block(listener) }
    }

    /** Сбой стадии -> [PipelineException]; отмена пробрасывается как есть. */
    private suspend fun <T> fatal(stage: Stage, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw PipelineException(stage, "Стадия ${stage.key} завершилась ошибкой: ${e.message ?: e::class.simpleName}", e)
    }

    /** Сбой стадии -> предупреждение и значение по умолчанию. */
    private suspend fun <T> soft(stage: Stage, warnings: MutableList<String>, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        warnings += "Стадия ${stage.key} пропущена: ${e.message ?: e::class.simpleName}"
        fallback
    }

    /** Зажимает отрезки VAD в [0, duration], выкидывает пустые, сортирует. */
    private fun sanitizeSpans(spans: List<TimeSpan>, durationMs: Long): List<TimeSpan> =
        spans.mapNotNull {
            val s = it.startMs.coerceIn(0, durationMs)
            val e = it.endMs.coerceIn(0, durationMs)
            if (e > s) TimeSpan(s, e) else null
        }.sortedBy { it.startMs }

    /** `use`, который не прячет основное исключение закрытием и не падает на close(). */
    private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
        try {
            return block(this)
        } finally {
            try {
                close()
            } catch (_: Throwable) {
                // выгрузка модели не должна ронять обработку
            }
        }
    }

    private val Stage.key: String get() = name.lowercase()

    /** Потокобезопасный агрегатор прогресса: общий прогресс монотонен. */
    private class Tracker(private val listener: PipelineProgressListener?) {
        private val fractions = FloatArray(Stage.entries.size)
        private var lastOverall = 0f
        private val lock = Any()

        fun report(stage: Stage, fraction: Float) {
            val l = listener ?: return
            synchronized(lock) {
                val f = fraction.coerceIn(0f, 1f)
                val i = stage.ordinal
                if (f > fractions[i]) fractions[i] = f
                var sum = 0f
                for (s in Stage.entries) sum += WEIGHTS.getValue(s) * fractions[s.ordinal]
                val overall = maxOf(lastOverall, sum.coerceIn(0f, 1f))
                lastOverall = overall
                l.onProgress(stage, fractions[i], overall)
            }
        }

        /** Listener для движка: сначала проверяет отмену (бросает CancellationException), потом репортит. */
        fun listener(stage: Stage, job: kotlinx.coroutines.Job) = ProgressListener { _, fraction ->
            job.ensureActive()
            report(stage, fraction)
        }
    }

    private companion object {
        val WEIGHTS: Map<Stage, Float> = mapOf(
            Stage.DECODE to 0.05f,
            Stage.DENOISE to 0.05f,
            Stage.VAD to 0.03f,
            Stage.LID to 0.01f,
            Stage.ASR to 0.54f,
            Stage.DIARIZATION to 0.20f,
            Stage.ALIGN to 0.02f,
            Stage.PUNCTUATION to 0.08f,
            Stage.EXPORT to 0.02f,
        )
    }
}
