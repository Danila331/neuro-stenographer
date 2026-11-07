package ru.neurosteno.sherpa

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import ru.neurosteno.core.LiveConfig
import ru.neurosteno.core.live.LiveUpdate
import ru.neurosteno.core.live.StreamingAsr
import ru.neurosteno.core.live.StreamingModelFiles

/**
 * Потоковая ASR на sherpa-onnx (`OnlineRecognizer`, transducer zipformer) для живой расшифровки при записи (B13).
 * Принимает 16 кГц float порциями; после каждой порции декодирует все готовые окна. Конец фразы определяет сам движок
 * (endpoint detection, правила из [LiveConfig.endpoint]) -- тогда фраза становится финальной и поток сбрасывается.
 *
 * Не потокобезопасен (один рабочий поток [ru.neurosteno.core.live.LivePreview]); [close] освобождает нативные ресурсы --
 * вызывать сразу после остановки записи, до загрузки моделей полной обработки.
 */
class SherpaStreamingAsr(
    cfg: LiveConfig,
    root: ModelRoot,
    numThreads: Int,
) : StreamingAsr {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private var lastPartial = ""
    private var closed = false

    init {
        val dir = root.directory(requireNotNull(cfg.modelDir) { "live.model_dir не задан" })
        val files = StreamingModelFiles.resolve(dir.list()?.toList().orEmpty())
        fun path(n: String) = File(dir, n).absolutePath
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(path(files.encoder), path(files.decoder), path(files.joiner)),
            tokens = path(files.tokens),
            numThreads = numThreads,
            provider = "cpu",
        )
        val e = cfg.endpoint
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = model,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, e.startSilenceS.toFloat(), 0f),           // долгая тишина без речи
                rule2 = EndpointRule(true, e.minTrailingSilenceS.toFloat(), 0f),      // пауза после речи
                rule3 = EndpointRule(false, 0f, e.maxUtteranceS.toFloat()),           // слишком длинная фраза
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        recognizer = OnlineRecognizer(null, config)
        stream = try {
            recognizer.createStream("")
        } catch (t: Throwable) {
            recognizer.release()
            throw t
        }
    }

    override fun accept(samples: FloatArray): LiveUpdate? {
        if (closed || samples.isEmpty()) return null
        stream.acceptWaveform(samples, SAMPLE_RATE)
        decodeReady()
        val text = recognizer.getResult(stream).text.trim()
        if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream)
            lastPartial = ""
            return LiveUpdate(partial = "", finalPhrase = text.ifEmpty { null })
        }
        if (text == lastPartial) return null
        lastPartial = text
        return LiveUpdate(partial = text)
    }

    override fun finish(): LiveUpdate? {
        if (closed) return null
        // дополняем тишиной, чтобы последние токены вышли из буфера признаков, и закрываем поток
        stream.acceptWaveform(FloatArray(TAIL_SAMPLES), SAMPLE_RATE)
        stream.inputFinished()
        decodeReady()
        val text = recognizer.getResult(stream).text.trim()
        lastPartial = ""
        return LiveUpdate(partial = "", finalPhrase = text.ifEmpty { null })
    }

    private fun decodeReady() {
        var guard = 0
        while (recognizer.isReady(stream) && guard++ < MAX_DECODE_STEPS) recognizer.decode(stream)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val TAIL_SAMPLES = 6_400 // 0.4 с
        const val MAX_DECODE_STEPS = 400
    }
}
