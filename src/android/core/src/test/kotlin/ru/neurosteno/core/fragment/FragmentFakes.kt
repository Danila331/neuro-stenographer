package ru.neurosteno.core.fragment

import java.util.Collections
import kotlin.math.roundToLong
import ru.neurosteno.core.AsrEngine
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.Diarizer
import ru.neurosteno.core.LanguageId
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.Stage
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.Vad
import ru.neurosteno.core.Word

/**
 * «Мир» для тестов повторной обработки: аудио -- рампа, значение сэмпла кодирует АБСОЛЮТНОЕ время (мс / 100000),
 * поэтому фейковые движки по первому сэмплу куска узнают, какой отрезок записи им дали. ASR -- «оракул»: отдаёт слова
 * истины, целиком попавшие в кусок (время относительно куска); диаризатор -- истинные turns; эмбеддинг клипа -- ось
 * истинного говорящего в момент начала клипа.
 */
internal class World(val durationMs: Long = 60_000) {
    val audio: AudioBuffer = AudioBuffer(FloatArray((durationMs * 16).toInt()) { i -> (i / 16.0 / 100_000.0).toFloat() })

    /** Истинные слова (абсолютное время) по языкам. */
    var words: Map<String, List<Word>> = mapOf("ru" to emptyList(), "en" to emptyList())

    /** Истинные говорящие: (start, end, говорящий). */
    var speakers: List<Triple<Long, Long, Int>> = emptyList()

    /** Начало куска (мс) по первому сэмплу. */
    fun offsetOf(a: AudioBuffer): Long = if (a.samples.isEmpty()) 0 else (a.samples[0] * 100_000.0).roundToLong()

    fun speakerAt(t: Long): Int? = speakers.firstOrNull { t >= it.first && t < it.second }?.third
}

internal class WorldVad(private val w: World, var noSpeech: Boolean = false) : Vad {
    val seen = Collections.synchronizedList(ArrayList<Pair<Long, Long>>()) // (offset, durationMs)
    override fun detect(audio: AudioBuffer): List<TimeSpan> {
        seen += w.offsetOf(audio) to audio.durationMs
        return if (noSpeech) emptyList() else listOf(TimeSpan(0, audio.durationMs))
    }
    override fun close() = Unit
}

internal class WorldAsr(private val w: World, private val language: String, private val log: MutableList<String>) : AsrEngine {
    override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word> {
        progress.onProgress(Stage.ASR, 0.5f)
        val off = w.offsetOf(audio)
        log += "$language@$off+${audio.durationMs}"
        val out = w.words.getValue(language)
            .filter { it.startMs >= off && it.endMs <= off + audio.durationMs }
            .map { it.copy(startMs = it.startMs - off, endMs = it.endMs - off) }
        progress.onProgress(Stage.ASR, 1f)
        return out
    }
    override fun close() = Unit
}

internal class WorldDiarizer(
    private val w: World,
    /** Истинный говорящий -> номер в диаризации фрагмента (произвольный, чтобы проверить сопоставление). */
    private val localId: Map<Int, Int>,
    private val withEmbeddings: Boolean = true,
    private val dim: Int = 8,
) : Diarizer {
    var requested: Int? = -1
    var diarizeCalls = 0
    var embeddingCalls = 0
    override fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener): List<SpeakerTurn> {
        diarizeCalls++
        requested = numSpeakers
        progress.onProgress(Stage.DIARIZATION, 0.5f)
        val off = w.offsetOf(audio)
        val end = off + audio.durationMs
        return w.speakers.mapNotNull { (s, e, spk) ->
            val a = maxOf(s, off)
            val b = minOf(e, end)
            if (b > a) SpeakerTurn(localId.getValue(spk), a - off, b - off) else null
        }
    }
    override fun speakerEmbeddings(clips: List<AudioBuffer>): List<FloatArray?> {
        embeddingCalls++
        if (!withEmbeddings) return emptyList()
        return clips.map { c ->
            val spk = w.speakerAt(w.offsetOf(c) + 5) ?: return@map null
            FloatArray(dim) { if (it == spk % dim) 1f else 0.02f }
        }
    }
    override fun close() = Unit
}

internal class FixedLid(private val answer: String) : LanguageId {
    override fun detect(audio: AudioBuffer, speech: List<TimeSpan>): String = answer
    override fun close() = Unit
}
