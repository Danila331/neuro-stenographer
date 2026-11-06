package ru.neurosteno.core

/** Источник аудио. В core непрозрачен; Android-модуль `:audio` добавляет реализацию для `Uri`. */
interface AudioInput {
    /** Человекочитаемое имя для логов/UI. */
val displayName: String
}

data class FileAudioInput(val path: String) : AudioInput {
    override val displayName: String get() = path.substringAfterLast('/')
}

/** Декодер любого поддерживаемого формата в mono 16 kHz float PCM. */
interface AudioDecoder {
fun decode(input: AudioInput, progress: ProgressListener): AudioBuffer
}

/** Опциональное шумоподавление; вызывается, только если оценка SNR ниже порога. */
interface Denoiser : AutoCloseable {
fun denoise(audio: AudioBuffer, progress: ProgressListener): AudioBuffer
}

interface Vad : AutoCloseable {
fun detect(audio: AudioBuffer): List<TimeSpan>
}

interface AsrEngine : AutoCloseable {
    /** Возвращает слова с АБСОЛЮТНЫМ временем (от начала файла). */
fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word>
}

interface Diarizer : AutoCloseable {
    /** numSpeakers == null -> автоопределение. Turns могут пересекаться. */
fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener): List<SpeakerTurn>
}

interface Punctuator : AutoCloseable {
    /** Только пунктуация/регистр; последовательность нормализованных слов обязана сохраниться. */
fun punctuate(utterances: List<Utterance>, progress: ProgressListener): List<Utterance>
}

interface LlmAssistant : AutoCloseable {
fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit = {}): String
}
