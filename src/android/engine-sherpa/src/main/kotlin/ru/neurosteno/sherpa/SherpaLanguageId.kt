package ru.neurosteno.sherpa

import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import java.io.File
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.LanguageId
import ru.neurosteno.core.TimeSpan

/**
 * Определение языка Whisper-моделью (tiny/base, многоязычная) из sherpa-onnx по первым ~10 с речи.
 * `language.lid_model` -- каталог с `*encoder*.onnx` и `*decoder*.onnx` (предпочтительно int8).
 * Возвращает код языка модели ("ru", "en", ...); оркестратор нормализует всё, что не "en", к "ru".
 */
class SherpaLanguageId(
    private val modelDir: String,
    private val root: ModelRoot,
    private val numThreads: Int = 1,
    private val maxSeconds: Int = 10,
) : LanguageId {

    private var native: SpokenLanguageIdentification? = null

    private fun native(): SpokenLanguageIdentification {
        native?.let { return it }
        val dir = root.directory(modelDir)
        val names = dir.list()?.toList().orEmpty().filter { it.endsWith(".onnx") }
        fun pick(word: String) = names.filter { it.contains(word) }.sortedByDescending { it.contains("int8") }.firstOrNull()
            ?: throw java.io.FileNotFoundException("LID: нет *$word*.onnx в $modelDir")
        val config = SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(
                encoder = File(dir, pick("encoder")).absolutePath,
                decoder = File(dir, pick("decoder")).absolutePath,
            ),
            numThreads = numThreads,
        )
        return SpokenLanguageIdentification(null, config).also { native = it }
    }

    override fun detect(audio: AudioBuffer, speech: List<TimeSpan>): String {
        val limit = maxSeconds * audio.sampleRate
        val buf = FloatArray(minOf(limit, audio.samples.size))
        var n = 0
        for (s in speech) {
            if (n >= buf.size) break
            val from = (s.startMs * audio.sampleRate / 1000).toInt().coerceIn(0, audio.samples.size)
            val to = (s.endMs * audio.sampleRate / 1000).toInt().coerceIn(from, audio.samples.size)
            val len = minOf(to - from, buf.size - n)
            System.arraycopy(audio.samples, from, buf, n, len)
            n += len
        }
        if (n < audio.sampleRate / 2) return "ru" // меньше полсекунды речи -- не гадаем
        val lid = native()
        val stream = lid.createStream()
        try {
            stream.acceptWaveform(buf.copyOf(n), audio.sampleRate)
            return lid.compute(stream).trim().lowercase()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        native?.release()
        native = null
    }
}
