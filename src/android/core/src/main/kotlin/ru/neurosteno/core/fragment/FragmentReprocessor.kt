package ru.neurosteno.core.fragment

import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import ru.neurosteno.core.Aligner
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.EngineProviders
import ru.neurosteno.core.GuardedPunctuator
import ru.neurosteno.core.LanguageConfig
import ru.neurosteno.core.PipelineException
import ru.neurosteno.core.PipelineProgressListener
import ru.neurosteno.core.PipelineSettings
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.SnrEstimator
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.Stage
import ru.neurosteno.core.TextGuard
import ru.neurosteno.core.TextNormalizer
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptJson
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word
import ru.neurosteno.core.audio.AudioNormalizer
import ru.neurosteno.core.postprocess.Postprocess
import ru.neurosteno.core.speakerLabel

/** Что пользователь просит пересчитать. Времена -- абсолютные (мс от начала записи). */
data class FragmentRequest(
    val startMs: Long,
    val endMs: Long,
    /** `null`/`"auto"` -- LID по фрагменту (если доступен), иначе язык расшифровки; `"ru"` | `"en"` -- принудительно. */
    val language: String? = null,
    /** Число говорящих в фрагменте: `null` -- авто, `1` -- один говорящий (диаризация не запускается). Учитывается только при [recomputeSpeakers]. */
    val numSpeakers: Int? = null,
    /**
     * false (по умолчанию) -- говорящие берутся из старых реплик по времени (быстро, только ASR);
     * true -- диаризация отрезка и сопоставление говорящих с глобальными (по эмбеддингам, запасной способ -- по времени).
     */
    val recomputeSpeakers: Boolean = false,
    /** Речь не найдена: false -- старые реплики остаются (результат [FragmentOutcome.NO_SPEECH_KEPT]); true -- реплики удаляются. */
    val removeIfNoSpeech: Boolean = false,
)

/** Настройки алгоритма (не пользовательские). */
data class FragmentTuning(
    /** Контекст вокруг отрезка, мс (слова, чей центр лежит вне отрезка, отбрасываются). */
    val paddingMs: Long = 300,
    /** Порог косинусной близости эмбеддингов ([SpeakerMatcher.DEFAULT_THRESHOLD]). */
    val matchThreshold: Double = SpeakerMatcher.DEFAULT_THRESHOLD,
    /** Минимум речи говорящего в фрагменте, чтобы считать его эмбеддинг (иначе -- сопоставление по времени). */
    val minEmbeddingSpeechMs: Long = 1_500,
    /** Сколько речи брать для эмбеддинга (эталон глобального говорящего и локальный клип). */
    val embeddingSpeechMs: Long = 20_000,
    /** Не брать в эталон реплики короче. */
    val minReferenceUtteranceMs: Long = 700,
    /** Автоопределение языка (LID) доверяем только при такой длине речи в отрезке; короче -- язык расшифровки. */
    val minLidSpeechMs: Long = 3_000,
)

enum class FragmentOutcome {
    /** Старые реплики заменены новыми. */
    REPLACED,

    /** Старых реплик в отрезке не было, найдена и добавлена новая речь. */
    INSERTED,

    /** Речь не найдена, расшифровка НЕ изменена. */
    NO_SPEECH_KEPT,

    /** Речь не найдена, старые реплики удалены (`removeIfNoSpeech = true`). */
    NO_SPEECH_REMOVED,

    /** Некорректный запрос (пустой/вне записи отрезок, `numSpeakers < 1`), расшифровка НЕ изменена. */
    INVALID_REQUEST,
}

/** Как локальный говорящий фрагмента сопоставлен с говорящим расшифровки. */
data class SpeakerAssignment(
    /** Номер говорящего в диаризации фрагмента (0 -- «говорящий не пересчитывался»). */
    val localSpeaker: Int,
    /** Идентификатор в расшифровке ДО перенумерации (старый `speaker_N` либо новый). */
    val speakerId: String,
    val method: SpeakerMatcher.Method?,
    val score: Double?,
)

class FragmentResult(
    /** Новая расшифровка (инварианты §1 соблюдены; `meta` -- прежний, времена повторной обработки -- в [stageMs]). */
    val transcript: Transcript,
    /** JSON строго по ТЗ. */
    val json: String,
    val outcome: FragmentOutcome,
    /** Человекочитаемое пояснение для UI (на русском). */
    val message: String,
    /** Реально обработанный диапазон (запрошенный отрезок, расширенный до границ заменённых реплик), без паддинга. */
    val range: TimeSpan?,
    /** Удалённые старые реплики (для «отменить»). */
    val removed: List<Utterance>,
    /** Добавленные реплики в том виде, как они лежат в [transcript] (с итоговыми `speaker_N`). */
    val added: List<Utterance>,
    /**
     * Идентификатор до перенумерации -> итоговый `speaker_N` (перенумерация по ТЗ: по первому появлению). Только говорящие,
     * оставшиеся в расшифровке; новые говорящие фрагмента присутствуют под своим временным id из [speakerAssignments].
     * Нужен UI, чтобы перенести пользовательские имена говорящих.
     */
    val speakerRemap: Map<String, String>,
    /** Как говорящие, реально попавшие в новые реплики, сопоставлены с расшифровкой (локальные говорящие без слов не перечисляются). */
    val speakerAssignments: List<SpeakerAssignment>,
    /** Язык, на котором распознавали фрагмент; `null`, если не распознавали. */
    val language: String?,
    val warnings: List<String>,
    /** Время стадий фрагмента, мс (decode = чтение отрезка и эталонов), плюс `total`. */
    val stageMs: Map<String, Long>,
    val processingMs: Long,
) {
    val changed: Boolean get() = outcome == FragmentOutcome.REPLACED || outcome == FragmentOutcome.INSERTED || outcome == FragmentOutcome.NO_SPEECH_REMOVED
}

/**
 * Повторная обработка фрагмента (BACKLOG B37): VAD -> (LID) -> ASR [ [ диаризация + эмбеддинги] -> Aligner -> пунктуация
 * ТОЛЬКО на отрезке `[start - padding, end + padding]`, времена переводятся в абсолютные, реплики, пересекающиеся с
 * отрезком, заменяются. Остальная расшифровка не затрагивается (кроме перенумерации `speaker_N` по ТЗ -- см. [FragmentResult.speakerRemap]).
 *
 * Поведение:
 *  - заменяются все реплики, у которых есть пересечение с `[start, end]` (касание границей -- нет); обрабатываемый
 *    диапазон расширяется до их границ ([FragmentResult.range]); слова нового распознавания, чей центр лежит вне
 *    диапазона (паддинг), отбрасываются, а совпадающие по тексту и времени со словами НЕзаменяемых реплик (речь соседа
 *    в зоне перекрытия) -- тоже, чтобы не дублировать;
 *  - говорящие: при `recomputeSpeakers = false` слова получают говорящих старых реплик (Aligner с их интервалами как turns);
 *    при `true` -- диаризация отрезка и [SpeakerMatcher] (эмбеддинги через [ru.neurosteno.core.Diarizer.speakerEmbeddings],
 *    запасной способ -- пересечение по времени, иначе новый `speaker_N`);
 *  - движки создаются [EngineProviders] перед стадией и закрываются сразу после, как в [ru.neurosteno.core.PipelineOrchestrator]
 *    (ASR и диаризация параллельно, затем пунктуатор); прогресс -- [PipelineProgressListener] (стадии DECODE..EXPORT,
 *    общий прогресс монотонный); отмена -- корутинами;
 *  - речь не найдена -> расшифровка не меняется ([FragmentOutcome.NO_SPEECH_KEPT]) либо реплики удаляются явным флагом;
 *  - сбой ASR -> [PipelineException] (исходная расшифровка неизменна -- она иммутабельна); сбой VAD/LID/диаризации/эмбеддингов/
 *    пунктуации -> предупреждение и деградация (VAD: весь отрезок -- речь; диаризация -> говорящие по старым репликам).
 */
class FragmentReprocessor(
    private val engines: EngineProviders,
    private val settings: PipelineSettings,
    private val tuning: FragmentTuning = FragmentTuning(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val aligner = Aligner(settings.align)
    private val matcher = SpeakerMatcher(tuning.matchThreshold)

    suspend fun reprocess(
        audio: AudioBuffer,
        transcript: Transcript,
        request: FragmentRequest,
        listener: PipelineProgressListener? = null,
    ): FragmentResult = reprocess(AudioSource.of(audio), transcript, request, listener)

    suspend fun reprocess(
        source: AudioSource,
        transcript: Transcript,
        request: FragmentRequest,
        listener: PipelineProgressListener? = null,
    ): FragmentResult {
        val tracker = Tracker(listener)
        val warnings: MutableList<String> = Collections.synchronizedList(ArrayList())
        val stageMs = Collections.synchronizedMap(LinkedHashMap<String, Long>())
        val t0 = nanoTime()
        val duration = source.durationMs

        suspend fun <T> timed(stage: Stage, block: suspend () -> T): T {
            val s = nanoTime()
            tracker.report(stage, 0f)
            try {
                return block()
            } finally {
                stageMs[stage.key] = (stageMs[stage.key] ?: 0L) + (nanoTime() - s) / 1_000_000
                tracker.report(stage, 1f)
            }
        }

        fun unchanged(outcome: FragmentOutcome, message: String, range: TimeSpan? = null, language: String? = null): FragmentResult {
            for (st in Stage.entries) tracker.report(st, 1f)
            val total = (nanoTime() - t0) / 1_000_000
            stageMs["total"] = total
            return FragmentResult(
                transcript, TranscriptJson.toJson(transcript.utterances, duration), outcome, message, range,
                emptyList(), emptyList(), identityRemap(transcript.utterances, duration), emptyList(), language,
                ArrayList(warnings), LinkedHashMap(stageMs), total,
            )
        }

        // ---- запрос ----
        val reqStart = request.startMs.coerceIn(0, duration)
        val reqEnd = request.endMs.coerceIn(0, duration)
        if (reqEnd <= reqStart) return unchanged(FragmentOutcome.INVALID_REQUEST, "Пустой отрезок или он вне записи: ${request.startMs}..${request.endMs} мс (длительность $duration мс)")
        if (request.numSpeakers != null && request.numSpeakers < 1) return unchanged(FragmentOutcome.INVALID_REQUEST, "Число говорящих должно быть не меньше 1")

        val old = transcript.utterances
        val replacedIdx = old.indices.filter { old[it].startMs < reqEnd && old[it].endMs > reqStart }
        val replaced = replacedIdx.map { old[it] }
        val effStart = minOf(reqStart, replaced.minOfOrNull { it.startMs } ?: reqStart).coerceAtLeast(0)
        val effEnd = maxOf(reqEnd, replaced.maxOfOrNull { it.endMs } ?: reqEnd).coerceAtMost(duration)
        val eff = TimeSpan(effStart, effEnd)
        val replacedSet = replacedIdx.toSet()
        val kept = old.filterIndexed { i, _ -> i !in replacedSet }
        val sliceStart = (effStart - tuning.paddingMs).coerceAtLeast(0)
        val sliceEnd = (effEnd + tuning.paddingMs).coerceAtMost(duration)

        // ---- чтение отрезка ----
        val slice = timed(Stage.DECODE) { blocking(tracker, Stage.DECODE) { source.read(sliceStart, sliceEnd) } }
        if (slice.samples.isEmpty()) return unchanged(FragmentOutcome.INVALID_REQUEST, "Не удалось прочитать аудио отрезка", eff)
        val sliceDur = slice.durationMs
        AudioNormalizer.apply(slice, settings.normalizeGain)

        // ---- denoise (как в основном пайплайне: только при низком SNR) ----
        val dnFactory = engines.denoiser
        val processed: AudioBuffer = if (settings.denoiseEnabled && dnFactory != null && SnrEstimator.estimateDb(slice) < settings.snrThresholdDb) {
            timed(Stage.DENOISE) {
                soft(Stage.DENOISE, warnings, slice) { blocking(tracker, Stage.DENOISE) { l -> dnFactory().use { it.denoise(slice, l) } } }
            }
        } else {
            tracker.report(Stage.DENOISE, 1f)
            slice
        }

        // ---- vad ----
        val speech: List<TimeSpan> = timed(Stage.VAD) {
            soft(Stage.VAD, warnings, listOf(TimeSpan(0, sliceDur))) {
                sanitize(blocking(tracker, Stage.VAD) { engines.vad().use { it.detect(processed) } }, sliceDur)
            }
        }
        var languageUsed: String? = null
        fun noSpeech(): FragmentResult {
            if (request.removeIfNoSpeech && replaced.isNotEmpty()) {
                tracker.report(Stage.EXPORT, 0f)
                return finish(kept, emptyList(), replaced, eff, duration, transcript, FragmentOutcome.NO_SPEECH_REMOVED,
                    "Речь в отрезке не найдена, ${replaced.size} реплик удалено", emptyList(), languageUsed, warnings, stageMs, t0, nanoTime)
                    .also { for (st in Stage.entries) tracker.report(st, 1f) }
            }
            return unchanged(FragmentOutcome.NO_SPEECH_KEPT,
                if (replaced.isEmpty()) "Речь в отрезке не найдена, расшифровка не изменена"
                else "Речь в отрезке не найдена, расшифровка не изменена (${replaced.size} реплик оставлено без изменений)", eff, languageUsed)
        }
        if (speech.isEmpty()) return noSpeech()

        // ---- язык ----
        val language = timed(Stage.LID) { chooseLanguage(request.language, transcript, processed, speech, tracker, warnings) }
        languageUsed = language

        // ---- глобальные говорящие и эталонные клипы ----
        val globalIds = LinkedHashMap<String, Int>() // speakerId -> индекс (1-based)
        old.forEach { globalIds.getOrPut(it.speakerId) { globalIds.size + 1 } }
        val idOf = HashMap<Int, String>().also { m -> globalIds.forEach { (id, i) -> m[i] = id } }
        val recompute = request.recomputeSpeakers && settings.diarizationEnabled
        if (request.recomputeSpeakers && !settings.diarizationEnabled) warnings += "Диаризация отключена в настройках -- говорящие взяты из старых реплик"
        if (!request.recomputeSpeakers && request.numSpeakers != null) warnings += "Число говорящих учитывается только при пересчёте говорящих -- проигнорировано"
        val refSpans: Map<Int, List<TimeSpan>> = if (recompute) referenceSpans(kept, globalIds, eff) else emptyMap()

        // ---- asr || диаризация (+ эмбеддинги) ----
        val (rawWords, diar) = coroutineScope {
            val asrJob = async {
                timed(Stage.ASR) {
                    fatal(Stage.ASR) {
                        blocking(tracker, Stage.ASR) { l -> engines.asr(language).use { it.transcribe(processed, speech, l) } }
                    }
                }
            }
            val diarJob = async {
                if (!recompute) {
                    tracker.report(Stage.DIARIZATION, 1f)
                    return@async null
                }
                timed(Stage.DIARIZATION) {
                    soft<DiarOutcome?>(Stage.DIARIZATION, warnings, null) {
                        blocking(tracker, Stage.DIARIZATION) { l ->
                            engines.diarizer().use { d -> diarizeAndEmbed(d, processed, sliceStart, sliceDur, eff, request.numSpeakers, refSpans, source, warnings, l) }
                        }
                    }
                }
            }
            asrJob.await() to diarJob.await()
        }

        // ---- слова: абсолютное время, фильтры ----
        coroutineContext.ensureActive()
        val shifted = rawWords.map { it.copy(startMs = it.startMs + sliceStart, endMs = it.endMs + sliceStart) }
        val inRange = shifted.filter { w ->
            val mid = (w.startMs + w.endMs) / 2
            w.text.isNotBlank() && mid >= effStart && mid <= effEnd
        }
        val deduped = dropEchoes(inRange, kept.filter { it.endMs > effStart && it.startMs < effEnd })
        if (deduped.size < inRange.size) warnings += "Отброшено слов, дублирующих соседние реплики в зоне перекрытия: ${inRange.size - deduped.size}"
        val words = if (settings.spellNumbers) Postprocess.spellNumbers(deduped) else deduped
        if (words.isEmpty()) return noSpeech()

        // ---- говорящие -> turns ----
        var nextIndex = globalIds.size
        fun freshIndex(): Int {
            val taken = globalIds.keys
            do nextIndex++ while (speakerLabel(nextIndex) in taken)
            idOf[nextIndex] = speakerLabel(nextIndex)
            return nextIndex
        }
        val assignments = ArrayList<SpeakerAssignment>()
        val turns: List<SpeakerTurn> = timed(Stage.ALIGN) {
            if (diar != null && diar.turns.isNotEmpty()) {
                val localIds = diar.turns.map { it.speaker }.distinct()
                val locals = localIds.map { id ->
                    SpeakerMatcher.Local(id, diar.localEmbeddings[id], diar.turns.filter { it.speaker == id }.mapNotNull { clip(it.startMs, it.endMs, eff) })
                }
                val globals = globalIds.values.map { gi ->
                    SpeakerMatcher.Global(gi, diar.globalEmbeddings[gi], replaced.filter { globalIds[it.speakerId] == gi }.map { TimeSpan(it.startMs, it.endMs) })
                }
                val matches = matcher.match(locals, globals).associateBy { it.localId }
                val toGlobal = localIds.associateWith { id ->
                    val m = matches.getValue(id)
                    val gi = m.globalIndex ?: freshIndex()
                    assignments += SpeakerAssignment(id, idOf.getValue(gi), m.method, m.score)
                    gi
                }
                diar.turns.map { it.copy(speaker = toGlobal.getValue(it.speaker)) }
            } else {
                if (recompute) warnings += "Диаризация отрезка не дала результата -- говорящие взяты из старых реплик"
                val t = replaced.map { SpeakerTurn(globalIds.getValue(it.speakerId), it.startMs, it.endMs) }
                if (t.isNotEmpty()) {
                    t.map { it.speaker }.distinct().forEach { assignments += SpeakerAssignment(0, idOf.getValue(it), null, null) }
                    t
                } else {
                    // вставка речи в пропуск: говорящий ближайшей реплики (нет реплик вообще -- speaker_1)
                    val mid = (effStart + effEnd) / 2
                    val nearest = old.minByOrNull { u -> if (mid in u.startMs..u.endMs) 0L else minOf(kotlin.math.abs(mid - u.startMs), kotlin.math.abs(mid - u.endMs)) }
                    val gi = nearest?.let { globalIds.getValue(it.speakerId) } ?: freshIndex()
                    assignments += SpeakerAssignment(0, idOf.getValue(gi), null, null)
                    listOf(SpeakerTurn(gi, effStart, effEnd))
                }
            }
        }

        // ---- align ----
        val aligned = aligner.align(words, turns, renumber = false).map { it.copy(speakerId = idOf[speakerIndexOf(it.speakerId)] ?: it.speakerId) }

        // ---- punctuation ----
        val punctuated = timed(Stage.PUNCTUATION) {
            val factory = engines.punctuator
            val allowed = settings.plan?.llmPunctuation ?: true
            val punct: ru.neurosteno.core.Punctuator? = if (factory != null && allowed) {
                soft<ru.neurosteno.core.Punctuator?>(Stage.PUNCTUATION, warnings, null) { blocking(tracker, Stage.PUNCTUATION) { factory(duration) } }
            } else {
                null
            }
            val result = if (factory != null && punct == null) {
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

        val outcome = if (replaced.isEmpty()) FragmentOutcome.INSERTED else FragmentOutcome.REPLACED
        val msg = if (replaced.isEmpty()) "Добавлено реплик: ${punctuated.size}" else "Заменено реплик: ${replaced.size} -> ${punctuated.size}"
        tracker.report(Stage.EXPORT, 0f)
        val used = punctuated.map { it.speakerId }.toSet()
        return finish(kept, punctuated, replaced, eff, duration, transcript, outcome, msg, assignments.filter { it.speakerId in used }, language, warnings, stageMs, t0, nanoTime)
            .also { tracker.report(Stage.EXPORT, 1f) }
    }

    // ---------------------------------------------------------------------------------------------

    private class DiarOutcome(
        val turns: List<SpeakerTurn>, // абсолютное время
        val localEmbeddings: Map<Int, FloatArray?>,
        val globalEmbeddings: Map<Int, FloatArray?>,
    )

    /** Диаризация отрезка и эмбеддинги (локальных говорящих и эталонов глобальных) одним экземпляром диаризатора. */
    private fun diarizeAndEmbed(
        d: ru.neurosteno.core.Diarizer,
        slice: AudioBuffer,
        sliceStart: Long,
        sliceDur: Long,
        eff: TimeSpan,
        numSpeakers: Int?,
        refSpans: Map<Int, List<TimeSpan>>,
        source: AudioSource,
        warnings: MutableList<String>,
        progress: ProgressListener,
    ): DiarOutcome {
        val rel = if (numSpeakers == 1) listOf(SpeakerTurn(0, 0, sliceDur)) else d.diarize(slice, numSpeakers, progress)
        val turns = rel.map { SpeakerTurn(it.speaker, it.startMs + sliceStart, it.endMs + sliceStart) }.filter { it.endMs > it.startMs }
        if (turns.isEmpty()) return DiarOutcome(emptyList(), emptyMap(), emptyMap())

        val keys = ArrayList<Pair<Boolean, Int>>() // (isGlobal, id)
        val clips = ArrayList<AudioBuffer>()
        for (id in turns.map { it.speaker }.distinct()) {
            val spans = turns.filter { it.speaker == id }.mapNotNull { clip(it.startMs, it.endMs, eff) }
            if (SpeakerMatcher.total(spans) < tuning.minEmbeddingSpeechMs) continue
            keys += false to id
            clips += concat(spans.map { slice.sliceMs(it.startMs - sliceStart, it.endMs - sliceStart) }, tuning.embeddingSpeechMs)
        }
        for ((gi, spans) in refSpans) {
            if (spans.isEmpty()) continue
            keys += true to gi
            clips += concat(spans.map { source.read(it.startMs, it.endMs) }, tuning.embeddingSpeechMs)
        }
        val embeddings: List<FloatArray?> = if (clips.isEmpty()) emptyList() else try {
            d.speakerEmbeddings(clips)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            warnings += "Эмбеддинги говорящих недоступны: ${e.message ?: e::class.simpleName}"
            emptyList()
        }
        val local = HashMap<Int, FloatArray?>()
        val global = HashMap<Int, FloatArray?>()
        if (embeddings.size == clips.size) {
            keys.forEachIndexed { i, (isGlobal, id) -> if (isGlobal) global[id] = embeddings[i] else local[id] = embeddings[i] }
        }
        return DiarOutcome(turns, local, global)
    }

    /**
     * Эталонные отрезки глобальных говорящих: самые длинные «чистые» (без пересечения с репликами других говорящих)
     * реплики ВНЕ заменяемого диапазона, пока не наберётся [FragmentTuning.embeddingSpeechMs]; каждая -- не длиннее 10 с.
     */
    private fun referenceSpans(kept: List<Utterance>, globalIds: Map<String, Int>, eff: TimeSpan): Map<Int, List<TimeSpan>> {
        val out = LinkedHashMap<Int, List<TimeSpan>>()
        for ((id, gi) in globalIds) {
            val mine = kept.filter { it.speakerId == id && it.endMs - it.startMs >= tuning.minReferenceUtteranceMs && (it.endMs <= eff.startMs || it.startMs >= eff.endMs) }
            if (mine.isEmpty()) continue
            val others = kept.filter { it.speakerId != id }
            val clean = mine.filter { u -> others.none { o -> o.startMs < u.endMs && o.endMs > u.startMs } }
            val pool = (clean.ifEmpty { mine }).sortedByDescending { it.endMs - it.startMs }
            val spans = ArrayList<TimeSpan>()
            var sum = 0L
            for (u in pool) {
                if (sum >= tuning.embeddingSpeechMs) break
                val len = minOf(u.endMs - u.startMs, MAX_REF_CLIP_MS)
                val start = u.startMs + (u.endMs - u.startMs - len) / 2
                spans += TimeSpan(start, start + len)
                sum += len
            }
            out[gi] = spans
        }
        return out
    }

    /** Слова нового распознавания, повторяющие (тот же текст, пересечение по времени) слова НЕзаменяемых соседних реплик. */
    private fun dropEchoes(words: List<Word>, neighbours: List<Utterance>): List<Word> {
        if (neighbours.isEmpty()) return words
        val nb = neighbours.flatMap { it.words }
        if (nb.isEmpty()) return words
        return words.filter { w ->
            val n = TextGuard.norm(w.text)
            n.isEmpty() || nb.none { o -> o.startMs < w.endMs && o.endMs > w.startMs && TextGuard.norm(o.text) == n }
        }
    }

    private fun clip(startMs: Long, endMs: Long, eff: TimeSpan): TimeSpan? {
        val s = maxOf(startMs, eff.startMs)
        val e = minOf(endMs, eff.endMs)
        return if (e > s) TimeSpan(s, e) else null
    }

    private fun concat(parts: List<AudioBuffer>, maxMs: Long): AudioBuffer {
        val sr = parts.firstOrNull()?.sampleRate ?: 16_000
        val cap = (maxMs * sr / 1000).toInt()
        val out = FloatArray(minOf(parts.sumOf { it.samples.size.toLong() }, cap.toLong()).toInt())
        var p = 0
        for (a in parts) {
            val n = minOf(a.samples.size, out.size - p)
            if (n <= 0) break
            System.arraycopy(a.samples, 0, out, p, n)
            p += n
        }
        return AudioBuffer(out, sr)
    }

    private fun AudioBuffer.sliceMs(startMs: Long, endMs: Long): AudioBuffer {
        val from = (startMs * sampleRate / 1000).toInt().coerceIn(0, samples.size)
        val to = (endMs * sampleRate / 1000).toInt().coerceIn(from, samples.size)
        return AudioBuffer(samples.copyOfRange(from, to), sampleRate)
    }

    private fun speakerIndexOf(label: String): Int = label.removePrefix("speaker_").toIntOrNull() ?: -1

    private suspend fun chooseLanguage(
        requested: String?,
        transcript: Transcript,
        audio: AudioBuffer,
        speech: List<TimeSpan>,
        tracker: Tracker,
        warnings: MutableList<String>,
    ): String {
        val req = requested?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "auto" }
        val lang = when {
            req == LanguageConfig.RU || req == LanguageConfig.EN -> req
            else -> {
                if (req != null) warnings += "Неизвестный язык '$requested' -- определяется автоматически"
                val lid = engines.languageId
                val canLid = lid != null && settings.languageMode == LanguageConfig.AUTO && LanguageConfig.EN in settings.supportedLanguages
                if (canLid && SpeakerMatcher.total(speech) >= tuning.minLidSpeechMs) {
                    soft(Stage.LID, warnings, transcript.meta.language) {
                        val raw = blocking(tracker, Stage.LID) { lid().use { it.detect(audio, speech) } }
                        if (raw.trim().lowercase().startsWith(LanguageConfig.EN)) LanguageConfig.EN else LanguageConfig.RU
                    }
                } else {
                    transcript.meta.language
                }
            }
        }
        if (lang !in settings.supportedLanguages) {
            warnings += "Язык '$lang' не поддерживается этим вариантом -- используется ru"
            return LanguageConfig.RU
        }
        return lang
    }

    // ---- вспомогательное (как в PipelineOrchestrator) ----

    private suspend fun <T> blocking(tracker: Tracker, stage: Stage, block: (ProgressListener) -> T): T {
        val listener = tracker.listener(stage, coroutineContext.job)
        return runInterruptible(dispatcher) { block(listener) }
    }

    private suspend fun <T> fatal(stage: Stage, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw PipelineException(stage, "Стадия ${stage.key} завершилась ошибкой: ${e.message ?: e::class.simpleName}", e)
    }

    private suspend fun <T> soft(stage: Stage, warnings: MutableList<String>, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        warnings += "Стадия ${stage.key} пропущена: ${e.message ?: e::class.simpleName}"
        fallback
    }

    private fun sanitize(spans: List<TimeSpan>, durationMs: Long): List<TimeSpan> =
        spans.mapNotNull {
            val s = it.startMs.coerceIn(0, durationMs)
            val e = it.endMs.coerceIn(0, durationMs)
            if (e > s) TimeSpan(s, e) else null
        }.sortedBy { it.startMs }

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

        fun listener(stage: Stage, job: kotlinx.coroutines.Job) = ProgressListener { _, fraction ->
            job.ensureActive()
            report(stage, fraction)
        }
    }

    private companion object {
        const val MAX_REF_CLIP_MS = 10_000L

        val WEIGHTS: Map<Stage, Float> = mapOf(
            Stage.DECODE to 0.04f,
            Stage.DENOISE to 0.03f,
            Stage.VAD to 0.03f,
            Stage.LID to 0.02f,
            Stage.ASR to 0.50f,
            Stage.DIARIZATION to 0.25f,
            Stage.ALIGN to 0.02f,
            Stage.PUNCTUATION to 0.08f,
            Stage.EXPORT to 0.03f,
        )
    }
}

/** Старый `speaker_N` -> `speaker_N` после [TranscriptJson.normalize] (перенумерация по первому появлению в отсортированной расшифровке). */
internal fun identityRemap(utterances: List<Utterance>, durationMs: Long): Map<String, String> {
    val ids = LinkedHashMap<String, String>()
    utterances.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        .forEach { ids.getOrPut(it.speakerId) { speakerLabel(ids.size + 1) } }
    return if (durationMs > 0) ids else emptyMap()
}

private fun finish(
    kept: List<Utterance>,
    added: List<Utterance>,
    removed: List<Utterance>,
    eff: TimeSpan,
    durationMs: Long,
    transcript: Transcript,
    outcome: FragmentOutcome,
    message: String,
    assignments: List<SpeakerAssignment>,
    language: String?,
    warnings: List<String>,
    stageMs: MutableMap<String, Long>,
    t0: Long,
    nanoTime: () -> Long,
): FragmentResult {
    val e0 = nanoTime()
    val merged = kept + added
    val final = TranscriptJson.normalize(merged, durationMs)
    val remap = identityRemap(merged, durationMs)
    val json = TranscriptJson.toJson(final, durationMs)
    // добавленные реплики в итоговом виде: по началу и словам
    val addedKeys = added.map { it.startMs to it.words }
    val addedFinal = final.filter { f -> addedKeys.any { (s, w) -> f.startMs == s && f.words == w } }
    stageMs[Stage.EXPORT.name.lowercase()] = (nanoTime() - e0) / 1_000_000
    val total = (nanoTime() - t0) / 1_000_000
    stageMs["total"] = total
    return FragmentResult(
        transcript = transcript.copy(utterances = final),
        json = json,
        outcome = outcome,
        message = message,
        range = eff,
        removed = removed,
        added = addedFinal,
        speakerRemap = remap,
        speakerAssignments = assignments,
        language = language,
        warnings = ArrayList(warnings),
        stageMs = LinkedHashMap(stageMs),
        processingMs = total,
    )
}
