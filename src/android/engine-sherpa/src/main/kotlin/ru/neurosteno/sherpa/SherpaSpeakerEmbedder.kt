package ru.neurosteno.sherpa

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import ru.neurosteno.core.SpeakerEmbedder

/**
 * Эмбеддинги говорящих sherpa-onnx (`diarization.embedding`: campplus / eres2net / eres2netv2 -- по tier) для relabel (B46).
 * Один вызов -- один фрагмент 16 кГц; как в эталоне: create_stream -> accept_waveform -> input_finished -> compute.
 */
class SherpaSpeakerEmbedder(
    modelPath: String,
    root: ModelRoot,
    numThreads: Int = 1,
) : SpeakerEmbedder {
    private var extractor: SpeakerEmbeddingExtractor? =
        SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = root.file(modelPath).absolutePath, numThreads = numThreads))

    override fun embed(segment: FloatArray): FloatArray {
        val ex = checkNotNull(extractor) { "SherpaSpeakerEmbedder закрыт" }
        val stream = ex.createStream()
        try {
            stream.acceptWaveform(segment, SAMPLE_RATE)
            stream.inputFinished()
            return ex.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        extractor?.release()
        extractor = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
