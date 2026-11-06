package ru.neurosteno.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.Vad
import ru.neurosteno.core.VadConfig
import ru.neurosteno.core.asr.SpanOps
import com.k2fsa.sherpa.onnx.Vad as NativeVad

/**
 * Silero VAD через sherpa-onnx: окна по 512 отсчётов (16 кГц), сегменты -> [TimeSpan] с отступами `pad_ms`.
 * Параметры -- `vad.*` из конфига варианта. Модель создаётся на время вызова и освобождается сразу после.
 */
class SherpaVad(
    private val cfg: VadConfig,
    private val root: ModelRoot,
    private val numThreads: Int = 1,
) : Vad {

    override fun detect(audio: AudioBuffer): List<TimeSpan> {
        require(audio.sampleRate == SAMPLE_RATE) { "VAD ожидает 16 кГц, получено ${audio.sampleRate}" }
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = root.file(cfg.model).absolutePath,
                threshold = cfg.threshold.toFloat(),
                minSilenceDuration = cfg.minSilenceMs / 1000f,
                minSpeechDuration = cfg.minSpeechMs / 1000f,
                windowSize = WINDOW,
                maxSpeechDuration = cfg.maxSpeechS.toFloat(),
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = numThreads,
            provider = "cpu",
        )
        val vad = NativeVad(null, config)
        try {
            val spans = ArrayList<TimeSpan>()
            val samples = audio.samples
            var pos = 0
            while (pos + WINDOW <= samples.size) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("VAD отменён")
                vad.acceptWaveform(samples.copyOfRange(pos, pos + WINDOW))
                pos += WINDOW
                drain(vad, spans)
            }
            vad.flush()
            drain(vad, spans)
            return SpanOps.padAndMerge(spans, cfg.padMs.toLong(), audio.durationMs)
        } finally {
            vad.release()
        }
    }

    private fun drain(vad: NativeVad, out: MutableList<TimeSpan>) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val startMs = seg.start * 1000L / SAMPLE_RATE
            val endMs = (seg.start + seg.samples.size) * 1000L / SAMPLE_RATE
            if (endMs > startMs) out += TimeSpan(startMs, endMs)
        }
    }

    override fun close() = Unit // нативные ресурсы живут только внутри detect()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512
    }
}
