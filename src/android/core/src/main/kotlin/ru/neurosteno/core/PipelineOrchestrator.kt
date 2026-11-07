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
import ru.neurosteno.core.asr.ChunkPlanner
import ru.neurosteno.core.asr.VadChunking
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
    /** false -> диаризация не запускается (один говорящий). */
    val diarizationEnabled: Boolean = true,
    /** Пиковая нормализация после декодирования (до denoise/VAD/ASR/диаризации). */
    val normalizeGain: NormalizeGainConfig = NormalizeGainConfig(),
    /** LID на каждом VAD-чанке (при `languageMode == auto` и наличии английской ASR). */
    val perChunkLanguage: Boolean = false,
    /** Чанки основной ASR: максимум/минимум, секунд (`asr.chunk.*`). */
    val chunkMaxS: Double = 25.0,
    val chunkMinS: Double = 1.0,
    /** VAD: паддинг, rescue и режим упаковки (`vad.*`); false -- прежний ChunkPlanner внутри движка. */
    val vadPadMs: Int = 150,
    val vadRescue: VadRescueConfig = VadRescueConfig(),
    val packChunking: Boolean = false,
    /** `postprocess.strip_dashes`: off | after_terminal | all. */
    val stripDashes: String = "off",
    /** `postprocess.numbers = words`. */
    val spellNumbers: Boolean = false,
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
            diarizationEnabled = cfg.diarization.enabled,
            normalizeGain = cfg.audio.normalizeGain,
            perChunkLanguage = cfg.language.perChunk,
            chunkMaxS = cfg.asr.chunk.maxS,
            chunkMinS = cfg.asr.chunk.minS,
            vadPadMs = cfg.vad.padMs,
            vadRescue = cfg.vad.rescue,
            packChunking = cfg.vad.packChunking,
            stripDashes = cfg.postprocess.stripDashes,
            spellNumbers = cfg.postprocess.spellNumbers,
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
    /** Аргумент -- длительность записи в мс (для бюджета времени LLM). */
    val punctuator: ((audioDurationMs: Long) -> Punctuator)? = null,
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

        // ---- нормализация громкости (тихие записи: farfield, телефон) ----
        // scope=all -- все стадии видят нормализованный сигнал; asr -- копия только для ASR/LID (ниже); asr_chunk -- в движке
        val ng = settings.normalizeGain
        if (ng.active && ng.effectiveScope == "all") ru.neurosteno.core.audio.AudioNormalizer.apply(audio, ng)

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
        val sp: SpeechChunks = timed(Stage.VAD) { planSpeech(processed, tracker, warnings) }
        val speech = sp.speech
        val chunks = sp.chunks
        if (speech.isEmpty() || chunks.isEmpty()) {
            for (s in listOf(Stage.LID, Stage.ASR, Stage.DIARIZATION)) tracker.report(s, 1f)
            return finish(emptyList(), durationMs, LanguageConfig.RU, t0, stageMs, warnings, tracker)
        }

        // ---- копия с нормализацией для ASR/LID (scope=asr): держим только на время этих стадий ----
        var asrAudio: AudioBuffer = processed
        if (ng.active && ng.effectiveScope == "asr") {
            ru.neurosteno.core.audio.AudioNormalizer.scaledCopy(processed, ng)?.let { asrAudio = it }
        }

        // ---- language id ----
        val langPlan = timed(Stage.LID) { planLanguage(asrAudio, speech, chunks, tracker, warnings) }
        val language = langPlan.language

        // ---- asr || diarization ----
        val asrIn = asrAudio
        val (rawWords, turns) = coroutineScope {
            val asrJob = async {
                timed(Stage.ASR) {
                    fatal(Stage.ASR) { transcribeAll(asrIn, if (sp.packed) chunks else speech, langPlan, tracker) }
                }
            }
            val diarJob = async {
                if (!settings.diarizationEnabled) {
                    tracker.report(Stage.DIARIZATION, 1f)
                    return@async emptyList<SpeakerTurn>()
                }
                timed(Stage.DIARIZATION) {
                    soft(Stage.DIARIZATION, warnings, emptyList()) {
                        blocking(tracker, Stage.DIARIZATION) { l ->
                            // диаризация всегда читает ИСХОДНЫЙ (не нормализованный при scope=asr) сигнал
                            engines.diarizer().use { it.diarize(processed, settings.numSpeakers, l) }
                        }
                    }
                }
            }
            asrJob.await() to diarJob.await()
        } // здесь ASR и диаризация уже закрыты -- память свободна под LLM
        asrAudio = processed // копия для ASR больше не нужна
        val numbered = if (settings.spellNumbers) ru.neurosteno.core.postprocess.Postprocess.spellNumbers(rawWords) else rawWords
        val words = ru.neurosteno.core.postprocess.Postprocess.stripDashes(numbered, settings.stripDashes)

        // ---- align ----
        coroutineContext.ensureActive()
        val aligned = timed(Stage.ALIGN) { aligner.align(words, turns) }

        // ---- punctuation ----
        val punctuated = timed(Stage.PUNCTUATION) {
            val factory = engines.punctuator
            val allowed = settings.plan?.llmPunctuation ?: true
            if (factory != null && !allowed) warnings += "Пунктуация LLM отключена планом ресурсов"
            val punct: Punctuator? = if (factory != null && allowed) {
                soft<Punctuator?>(Stage.PUNCTUATION, warnings, null) { blocking(tracker, Stage.PUNCTUATION) { factory(durationMs) } }
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

    /** План языка: либо один язык на весь файл, либо (per_chunk) раздельные списки чанков ru/en. */
    private class LangPlan(val language: String, val ruSpans: List<TimeSpan>? = null, val enSpans: List<TimeSpan>? = null) {
        val perChunk: Boolean get() = ruSpans != null && enSpans != null
    }

    /** Речь и чанки ASR: pack -- по эталону refpipe (в отсчётах); legacy -- интервалы VAD, чанки режет движок. */
    private class SpeechChunks(val speech: List<TimeSpan>, val chunks: List<TimeSpan>, val packed: Boolean)

    private suspend fun planSpeech(audio: AudioBuffer, tracker: Tracker, warnings: MutableList<String>): SpeechChunks {
        val durationMs = audio.durationMs
        val whole = TimeSpan(0, durationMs)
        if (!settings.packChunking) {
            val speech = soft(Stage.VAD, warnings, listOf(whole)) {
                sanitizeSpans(blocking(tracker, Stage.VAD) { engines.vad().use { it.detect(audio) } }, durationMs)
            }
            val chunks = if (settings.perChunkLanguage) ChunkPlanner.plan(audio.samples, speech, (settings.chunkMaxS * 1000).toLong()) else speech
            return SpeechChunks(speech, chunks, packed = false)
        }
        val raw = soft<List<SampleSpan>?>(Stage.VAD, warnings, null) {
            blocking(tracker, Stage.VAD) { engines.vad().use { it.detectSamples(audio) } }
        } ?: listOf(SampleSpan(0, audio.samples.size)) // VAD не сработал -- вся запись как речь
        val plan = VadChunking.plan(audio.samples, raw, settings.vadPadMs, settings.vadRescue, settings.chunkMaxS, settings.chunkMinS)
        return SpeechChunks(plan.speech, plan.chunks, packed = true)
    }

    private suspend fun planLanguage(
        audio: AudioBuffer,
        speech: List<TimeSpan>,
        chunksIn: List<TimeSpan>,
        tracker: Tracker,
        warnings: MutableList<String>,
    ): LangPlan {
        val lidFactory = engines.languageId
        val perChunk = settings.perChunkLanguage && settings.languageMode == LanguageConfig.AUTO &&
            LanguageConfig.EN in settings.supportedLanguages && lidFactory != null
        if (!perChunk) return LangPlan(chooseLanguage(audio, speech, tracker, warnings))

        val chunks = chunksIn
        val preds: List<String?>? = soft<List<String?>?>(Stage.LID, warnings, null) {
            blocking(tracker, Stage.LID) { l ->
                lidFactory().use { lid ->
                    chunks.mapIndexed { i, c ->
                        l.onProgress(Stage.LID, i.toFloat() / chunks.size)
                        if (c.endMs - c.startMs < LanguageSmoother.MIN_LID_MS) null else lid.detect(audio, listOf(c))
                    }
                }
            }
        }
        if (preds == null) return LangPlan(LanguageConfig.RU)
        val labels = LanguageSmoother.smooth(preds, chunks.map { it.endMs - it.startMs })
        val ru = chunks.filterIndexed { i, _ -> labels[i] == LanguageConfig.RU }
        val en = chunks.filterIndexed { i, _ -> labels[i] == LanguageConfig.EN }
        val enMs = en.sumOf { it.endMs - it.startMs }
        val ruMs = ru.sumOf { it.endMs - it.startMs }
        return LangPlan(if (enMs > ruMs) LanguageConfig.EN else LanguageConfig.RU, ru, en)
    }

    /**
     * Распознавание. Один язык -- как раньше. Per-chunk: сначала русская ASR на ru-чанках и ВЫГРУЗКА, затем английская
     * на en-чанках (две ASR не держим в памяти одновременно); слова сливаются по времени.
     */
    private suspend fun transcribeAll(audio: AudioBuffer, speech: List<TimeSpan>, plan: LangPlan, tracker: Tracker): List<Word> {
        if (!plan.perChunk) {
            return blocking(tracker, Stage.ASR) { l -> engines.asr(plan.language).use { it.transcribe(audio, speech, l) } }
        }
        val groups = listOf(LanguageConfig.RU to plan.ruSpans!!, LanguageConfig.EN to plan.enSpans!!).filter { it.second.isNotEmpty() }
        val total = groups.sumOf { g -> g.second.sumOf { it.endMs - it.startMs } }.coerceAtLeast(1)
        val out = ArrayList<Word>()
        var done = 0L
        for ((lang, spans) in groups) {
            val share = spans.sumOf { it.endMs - it.startMs }
            val base = done.toFloat() / total
            val frac = share.toFloat() / total
            out += blocking(tracker, Stage.ASR) { l ->
                val scaled = ProgressListener { s, f -> l.onProgress(s, base + f * frac) }
                engines.asr(lang).use { it.transcribe(audio, spans, scaled) }
            }
            done += share
        }
        return out.sortedWith(compareBy({ it.startMs }, { it.endMs }))
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
