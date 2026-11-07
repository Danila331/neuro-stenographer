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
    /** Речевые интервалы в мс (с паддингом и склейкой самого движка) -- режим `vad.chunking = legacy`. */
    fun detect(audio: AudioBuffer): List<TimeSpan>

    /**
     * Сырые сегменты в ОТСЧЁТАХ без паддинга (режим `pack`: паддинг, rescue и упаковку в чанки делает [ru.neurosteno.core.asr.VadChunking]).
     * По умолчанию -- из [detect] (для фейков и простых реализаций).
     */
    fun detectSamples(audio: AudioBuffer): List<SampleSpan> =
        detect(audio).map { SampleSpan((it.startMs * audio.sampleRate / 1000).toInt(), (it.endMs * audio.sampleRate / 1000).toInt()) }
}

/** Определение языка по речи: "ru" | "en" (любой другой ответ трактуется как "ru"). */
interface LanguageId : AutoCloseable {
    fun detect(audio: AudioBuffer, speech: List<TimeSpan>): String
}

interface AsrEngine : AutoCloseable {
    /** Возвращает слова с АБСОЛЮТНЫМ временем (от начала файла). */
fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word>
}

interface Diarizer : AutoCloseable {
    /** numSpeakers == null -> автоопределение. Turns могут пересекаться. */
fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener): List<SpeakerTurn>

    /**
     * Эмбеддинги говорящих: по одному вектору на каждый клип речи ([clips] -- моно 16 кГц, речь одного человека).
     * `null` в элементе -- клип слишком короткий/не удалось; пустой список (по умолчанию) -- диаризатор не умеет
     * считать эмбеддинги (тогда [ru.neurosteno.core.fragment.FragmentReprocessor] сопоставляет говорящих по времени).
     * Нужен для сопоставления говорящих фрагмента с глобальными при повторной обработке.
     */
    fun speakerEmbeddings(clips: List<AudioBuffer>): List<FloatArray?> = emptyList()
}

/** Эмбеддинг говорящего для фрагмента 16 кГц (sherpa-onnx SpeakerEmbeddingExtractor); вектор не обязан быть нормирован. */
interface SpeakerEmbedder : AutoCloseable {
    fun embed(segment: FloatArray): FloatArray
}

interface Punctuator : AutoCloseable {
    /** Только пунктуация/регистр; последовательность нормализованных слов обязана сохраниться. */
fun punctuate(utterances: List<Utterance>, progress: ProgressListener): List<Utterance>
}

interface LlmAssistant : AutoCloseable {
fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit = {}): String
}
