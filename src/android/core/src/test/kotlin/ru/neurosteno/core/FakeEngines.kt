package ru.neurosteno.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Фейковые движки -- только для тестов (в main их нет). */
internal fun silence(ms: Long) = AudioBuffer(FloatArray((ms * 16).toInt()))

internal class FakeDecoder(var audio: AudioBuffer = silence(10_000), var fail: Throwable? = null) : AudioDecoder {
    override fun decode(input: AudioInput, progress: ProgressListener): AudioBuffer {
        progress.onProgress(Stage.DECODE, 0.5f)
        fail?.let { throw it }
        return audio
    }
}

internal class FakeVad(var spans: List<TimeSpan>? = null, var fail: Throwable? = null) : Vad {
    var seen: AudioBuffer? = null
    override fun detect(audio: AudioBuffer): List<TimeSpan> {
        seen = audio
        fail?.let { throw it }
        return spans ?: listOf(TimeSpan(0, audio.durationMs))
    }
    override fun close() = Unit
}

internal class FakeAsr(
    var words: List<Word> = listOf(Word("привет", 100, 600), Word("мир", 700, 1200)),
    var fail: Throwable? = null,
    private val rendezvous: CountDownLatch? = null,
) : AsrEngine {
    var startedAt = 0L
    var finishedAt = 0L
    override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word> {
        startedAt = System.nanoTime()
        rendezvous?.let { it.countDown(); check(it.await(5, TimeUnit.SECONDS)) { "diarizer did not start in parallel" } }
        progress.onProgress(Stage.ASR, 0.5f)
        fail?.let { throw it }
        Thread.sleep(20)
        progress.onProgress(Stage.ASR, 1f)
        finishedAt = System.nanoTime()
        return words
    }
    override fun close() = Unit
}

internal class FakeDiarizer(
    var turns: List<SpeakerTurn> = listOf(SpeakerTurn(0, 0, 10_000)),
    var fail: Throwable? = null,
    private val rendezvous: CountDownLatch? = null,
) : Diarizer {
    var requestedSpeakers: Int? = -1
    override fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener): List<SpeakerTurn> {
        requestedSpeakers = numSpeakers
        rendezvous?.let { it.countDown(); check(it.await(5, TimeUnit.SECONDS)) { "asr did not start in parallel" } }
        progress.onProgress(Stage.DIARIZATION, 0.5f)
        fail?.let { throw it }
        return turns
    }
    override fun close() = Unit
}

internal class FakeDenoiser : Denoiser {
    val called = AtomicBoolean(false)
    override fun denoise(audio: AudioBuffer, progress: ProgressListener): AudioBuffer {
        called.set(true)
        return AudioBuffer(FloatArray(audio.samples.size) { 0.123f })
    }
    override fun close() = Unit
}

internal class FakePunctuator(var transform: (Utterance) -> Utterance = { it }) : Punctuator {
    override fun punctuate(utterances: List<Utterance>, progress: ProgressListener) = utterances.map(transform)
    override fun close() = Unit
}
