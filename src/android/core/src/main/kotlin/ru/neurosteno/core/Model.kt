package ru.neurosteno.core

/** Моно PCM, float в [-1, 1]. */
class AudioBuffer(val samples: FloatArray, val sampleRate: Int = 16_000) {
val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/** Сегмент речи в ОТСЧЁТАХ 16 кГц: [start, end). */
data class SampleSpan(val start: Int, val end: Int)

data class TimeSpan(val startMs: Long, val endMs: Long) {
val durationMs: Long get() = endMs - startMs
}

data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float? = null,
)

data class SpeakerTurn(val speaker: Int, val startMs: Long, val endMs: Long)

data class Utterance(
    val speakerId: String, // "speaker_1"
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word>,
)

data class Transcript(val utterances: List<Utterance>, val meta: TranscriptMeta)

data class TranscriptMeta(
    val tier: String,
    val audioDurationMs: Long,
    val processingMs: Long,
    val stageMs: Map<String, Long>,
    val models: Map<String, String>,
    val appVersion: String,
    /** Язык, на котором распознавали ("ru" | "en"). */
    val language: String = "ru",
) {
    /** Real-time factor: время обработки / длительность аудио (0, если длительность неизвестна). */
val rtf: Double get() = if (audioDurationMs > 0) processingMs.toDouble() / audioDurationMs else 0.0
}

enum class Stage { DECODE, DENOISE, VAD, LID, ASR, DIARIZATION, ALIGN, PUNCTUATION, EXPORT }

fun interface ProgressListener {
fun onProgress(stage: Stage, fraction: Float)
}

fun speakerLabel(index: Int): String = "speaker_$index"
