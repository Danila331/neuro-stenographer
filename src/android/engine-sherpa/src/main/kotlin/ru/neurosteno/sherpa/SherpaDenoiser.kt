package ru.neurosteno.sherpa

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.Denoiser
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.Stage
import ru.neurosteno.core.audio.Resampler

/** Шумоподавление GTCRN (sherpa-onnx). Длинные записи обрабатываются блоками по [blockSeconds] с прогрессом/отменой. */
class SherpaDenoiser(
    private val modelPath: String,
    private val root: ModelRoot,
    private val numThreads: Int,
    private val blockSeconds: Int = 60,
) : Denoiser {

    private var native: OfflineSpeechDenoiser? = null

    private fun native(): OfflineSpeechDenoiser = native ?: OfflineSpeechDenoiser(
        null,
        OfflineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = root.file(modelPath).absolutePath),
                numThreads = numThreads,
                provider = "cpu",
            ),
        ),
    ).also { native = it }

    override fun denoise(audio: AudioBuffer, progress: ProgressListener): AudioBuffer {
        val dn = native()
        val block = blockSeconds * audio.sampleRate
        val out = FloatArray(audio.samples.size)
        var pos = 0
        progress.onProgress(Stage.DENOISE, 0f)
        while (pos < audio.samples.size) {
            val end = minOf(pos + block, audio.samples.size)
            val res = dn.run(audio.samples.copyOfRange(pos, end), audio.sampleRate)
            val cleaned = if (res.sampleRate == audio.sampleRate) res.samples
            else Resampler.resample(res.samples, res.sampleRate, audio.sampleRate)
            val n = minOf(cleaned.size, end - pos)
            System.arraycopy(cleaned, 0, out, pos, n)
            // хвост блока, если модель вернула меньше -- оставляем исходный сигнал
            if (n < end - pos) System.arraycopy(audio.samples, pos + n, out, pos + n, end - pos - n)
            pos = end
            progress.onProgress(Stage.DENOISE, pos.toFloat() / audio.samples.size)
        }
        return AudioBuffer(out, audio.sampleRate)
    }

    override fun close() {
        native?.release()
        native = null
    }
}
