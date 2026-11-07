package ru.neurosteno.sherpa

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.DiarizationConfig
import ru.neurosteno.core.Diarizer
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.Stage

/**
 * Диаризация sherpa-onnx: pyannote-segmentation-3.0 + speaker embedding + кластеризация.
 * `numSpeakers == null` -> число говорящих определяется порогом `clustering.threshold`.
 *
 * ВАЖНО про отмену: sherpa-onnx не умеет прерывать нативный `process` (возврат callback игнорируется), а callback
 * вызывается только на фазе эмбеддингов. Исключение из callback через JNI не пробрасываем -- запоминаем и
 * бросаем сразу после возврата из нативного кода, поэтому отмена на длинной записи может ждать конца текущего вызова.
 */
class SherpaDiarizer(
    private val cfg: DiarizationConfig,
    private val root: ModelRoot,
    defaultThreads: Int,
) : Diarizer {
    private val numThreads: Int = cfg.numThreads ?: defaultThreads


    private var engine: OfflineSpeakerDiarization? = null

    private fun engine(numSpeakers: Int?): OfflineSpeakerDiarization {
        val clustering = FastClusteringConfig(
            numClusters = numSpeakers ?: cfg.clustering.numSpeakers ?: -1,
            threshold = cfg.clustering.threshold.toFloat(),
        )
        engine?.let {
            it.setConfig(OfflineSpeakerDiarizationConfig(clustering = clustering)) // обновляется только clustering
            return it
        }
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = root.file(cfg.segmentation).absolutePath,
                    windowShiftRatio = cfg.windowShiftRatio.toFloat(),
                ),
                numThreads = numThreads,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = root.file(cfg.embedding).absolutePath,
                numThreads = numThreads,
                provider = "cpu",
            ),
            clustering = clustering,
            minDurationOn = cfg.minDurationOnS.toFloat(),
            minDurationOff = cfg.minDurationOffS.toFloat(),
        )
        return OfflineSpeakerDiarization(null, config).also { engine = it }
    }

    override fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener): List<SpeakerTurn> {
        val sd = engine(numSpeakers)
        require(audio.sampleRate == sd.sampleRate()) {
            "Диаризация ожидает ${sd.sampleRate()} Гц, получено ${audio.sampleRate}"
        }
        progress.onProgress(Stage.DIARIZATION, 0f)
        var pending: Throwable? = null
        val segments = sd.processWithCallback(audio.samples, { done, total, _ ->
            if (pending == null) {
                try {
                    progress.onProgress(Stage.DIARIZATION, if (total > 0) done.toFloat() / total else 0f)
                } catch (t: Throwable) {
                    pending = t
                }
            }
            0
        })
        pending?.let { throw it }
        progress.onProgress(Stage.DIARIZATION, 1f)

        val turns = segments.map {
            SpeakerTurn(it.speaker, (it.start * 1000f).toLong(), (it.end * 1000f).toLong())
        }.filter { it.endMs > it.startMs }
        return limitSpeakers(turns, cfg.clustering.maxSpeakers)
    }

    /**
     * Если кластеризация дала больше `max_speakers` говорящих, лишних (по суммарной длительности) присоединяем
     * к ближайшему по времени из оставшихся -- в расшифровке не должно появляться «призрачных» участников.
     */
    private fun limitSpeakers(turns: List<SpeakerTurn>, max: Int): List<SpeakerTurn> {
        if (max <= 0) return turns
        val total = turns.groupBy { it.speaker }.mapValues { (_, v) -> v.sumOf { it.endMs - it.startMs } }
        if (total.size <= max) return turns
        val keep = total.entries.sortedByDescending { it.value }.take(max).map { it.key }.toSet()
        val kept = turns.filter { it.speaker in keep }
        if (kept.isEmpty()) return turns
        return turns.map { t ->
            if (t.speaker in keep) t else {
                val mid = (t.startMs + t.endMs) / 2
                val nearest = kept.minBy { k ->
                    when {
                        mid < k.startMs -> k.startMs - mid
                        mid > k.endMs -> mid - k.endMs
                        else -> 0L
                    }
                }
                t.copy(speaker = nearest.speaker)
            }
        }
    }

    private var extractor: SpeakerEmbeddingExtractor? = null

    /**
     * Эмбеддинги говорящих (повторная обработка фрагмента, BACKLOG B37): тот же файл модели, что и в диаризации
     * (`diarization.embedding`), отдельный `SpeakerEmbeddingExtractor` -- создаётся при первом вызове и выгружается в [close].
     * Клип короче [MIN_EMBEDDING_SAMPLES] (0.5 с) -> `null`.
     */
    override fun speakerEmbeddings(clips: List<AudioBuffer>): List<FloatArray?> {
        if (clips.isEmpty()) return emptyList()
        val ex = extractor ?: SpeakerEmbeddingExtractor(
            null,
            SpeakerEmbeddingExtractorConfig(model = root.file(cfg.embedding).absolutePath, numThreads = numThreads, provider = "cpu"),
        ).also { extractor = it }
        return clips.map { clip ->
            if (clip.samples.size < MIN_EMBEDDING_SAMPLES) return@map null
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(clip.samples, clip.sampleRate)
                stream.inputFinished()
                if (ex.isReady(stream)) ex.compute(stream) else null
            } finally {
                stream.release()
            }
        }
    }

    override fun close() {
        engine?.release()
        engine = null
        extractor?.release()
        extractor = null
    }

    private companion object {
        const val MIN_EMBEDDING_SAMPLES = 8_000
    }
}
