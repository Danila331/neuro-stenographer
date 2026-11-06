package ru.neurosteno.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import ru.neurosteno.core.AsrConfig
import ru.neurosteno.core.AsrEngine
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.Stage
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.Word
import ru.neurosteno.core.asr.AsrModelFiles
import ru.neurosteno.core.asr.ChunkPlanner
import ru.neurosteno.core.asr.TokenWords
import kotlin.math.roundToLong

/**
 * ASR на sherpa-onnx (OfflineRecognizer): GigaAM CTC/RNNT (NeMo), zipformer-transducer, Whisper, Paraformer --
 * тип определяется полем `engine` конфига и составом файлов каталога модели ([AsrModelFiles]).
 *
 * Речь режется на чанки по паузам VAD ([ChunkPlanner], `asr.chunk.*`); каждый чанк декодируется отдельно,
 * слова получают АБСОЛЮТНОЕ время (смещение чанка + время токена). После каждого чанка вызывается [ProgressListener]
 * (он же точка отмены). Известные ограничения: sherpa-onnx не отдаёт confidence -> `Word.confidence == null`
 * и второй проход (`asr.second_pass`, trigger low_confidence) пока не поддержан; Whisper не отдаёт времена токенов --
 * слова равномерно распределяются по чанку.
 *
 * @param language "ru" | "en" -- используется Whisper (многоязычные модели) и логами.
 */
class SherpaAsr(
    private val cfg: AsrConfig,
    private val root: ModelRoot,
    private val numThreads: Int,
    private val language: String = "ru",
    /** true -> модель сама ставит знаки и регистр (`punctuation.source == asr_native`), слова сохраняют их. */
    private val keepPunct: Boolean = false,
    /** Отладка/диагностика: сырой результат распознавания каждого чанка (текст, токены, времена токенов в секундах). */
    private val onRawResult: ((text: String, tokens: List<String>, timestamps: FloatArray) -> Unit)? = null,
) : AsrEngine {

    private var recognizer: OfflineRecognizer? = null

    private fun recognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val dir = root.directory(cfg.modelDir)
        val names = dir.list()?.toList().orEmpty()
        val files = AsrModelFiles.resolve(cfg.engine, names)
        fun path(n: String) = java.io.File(dir, n).absolutePath

        val model = OfflineModelConfig(numThreads = numThreads, provider = "cpu", tokens = path(files.tokens))
        when (files.kind) {
            AsrModelFiles.Kind.NEMO_CTC -> model.nemo = OfflineNemoEncDecCtcModelConfig(model = path(files.model!!))
            AsrModelFiles.Kind.NEMO_TRANSDUCER -> {
                model.transducer = OfflineTransducerModelConfig(path(files.encoder!!), path(files.decoder!!), path(files.joiner!!))
                model.modelType = "nemo_transducer"
            }
            AsrModelFiles.Kind.TRANSDUCER -> {
                model.transducer = OfflineTransducerModelConfig(path(files.encoder!!), path(files.decoder!!), path(files.joiner!!))
                model.modelType = "transducer"
            }
            AsrModelFiles.Kind.WHISPER -> {
                model.whisper = OfflineWhisperModelConfig(
                    encoder = path(files.encoder!!),
                    decoder = path(files.decoder!!),
                    language = language,
                    task = "transcribe",
                )
                model.modelType = "whisper"
            }
            AsrModelFiles.Kind.PARAFORMER -> {
                model.paraformer = OfflineParaformerModelConfig(model = path(files.model!!))
                model.modelType = "paraformer"
            }
        }
        val hotwords = root.optionalFile(cfg.hotwordsFile)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = if (files.kind == AsrModelFiles.Kind.NEMO_TRANSDUCER) 64 else 80), // как в эталоне
            modelConfig = model,
            decodingMethod = cfg.decoding,
            maxActivePaths = cfg.maxActivePaths,
            hotwordsFile = hotwords?.absolutePath ?: "",
            hotwordsScore = cfg.hotwordsScore.toFloat(),
        )
        return OfflineRecognizer(null, config).also { recognizer = it }
    }

    override fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word> {
        require(audio.sampleRate == SAMPLE_RATE) { "ASR ожидает 16 кГц, получено ${audio.sampleRate}" }
        val rec = recognizer()
        val chunks = ChunkPlanner.plan(audio.samples, speech, (cfg.chunk.maxS * 1000).roundToLong())
        val minSamples = (cfg.chunk.minS * SAMPLE_RATE).toInt()
        val words = ArrayList<Word>()
        progress.onProgress(Stage.ASR, 0f)
        chunks.forEachIndexed { i, chunk ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException("ASR отменён")
            val from = (chunk.startMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, audio.samples.size)
            val to = (chunk.endMs * SAMPLE_RATE / 1000).toInt().coerceIn(from, audio.samples.size)
            if (to > from) {
                val stream = rec.createStream()
                try {
                    var seg = audio.samples.copyOfRange(from, to)
                    // как в эталоне: слишком короткий чанк дополняется тишиной до chunk.min_s (границы чанка не двигаются)
                    if (seg.size < minSamples) seg = seg.copyOf(minSamples)
                    stream.acceptWaveform(seg, SAMPLE_RATE)
                    rec.decode(stream)
                    val r = rec.getResult(stream)
                    onRawResult?.invoke(r.text, r.tokens.toList(), r.timestamps)
                    words += wordsOf(r.text, r.tokens.toList(), r.timestamps, r.durations, chunk.startMs, chunk.endMs)
                } finally {
                    stream.release()
                }
            }
            progress.onProgress(Stage.ASR, (i + 1).toFloat() / chunks.size)
        }
        return dropOverlapDuplicates(words)
    }

    /** Токены -> слова по правилам эталона; без времён токенов (Whisper) -- равномерная раскладка по чанку. */
    private fun wordsOf(text: String, tokens: List<String>, ts: FloatArray, durations: FloatArray, startMs: Long, endMs: Long): List<Word> {
        if (tokens.isEmpty() || ts.size < tokens.size) return TokenWords.spread(text, startMs, endMs, keepPunct)
        return TokenWords.build(
            tokens = tokens,
            timestampsSec = ts,
            chunkStartMs = startMs,
            chunkEndMs = endMs,
            durationsSec = durations.takeIf { it.size >= tokens.size },
            tailMs = cfg.wordTailMs.toLong(),
            startOffsetMs = cfg.wordStartOffsetMs.toLong(),
            keepPunct = keepPunct,
        )
    }

    /** При overlap_ms > 0 соседние чанки могут дать одно и то же слово дважды -- убираем повтор на стыке. */
    private fun dropOverlapDuplicates(words: List<Word>): List<Word> {
        if (cfg.chunk.overlapMs <= 0) return words
        val out = ArrayList<Word>(words.size)
        for (w in words) {
            val prev = out.lastOrNull()
            if (prev != null && prev.text.equals(w.text, ignoreCase = true) && w.startMs < prev.endMs) continue
            out += w
        }
        return out
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
