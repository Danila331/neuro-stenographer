package ru.neurosteno.core.audio

import ru.neurosteno.core.AudioBuffer

/** Преобразования PCM без Android-зависимостей (используются декодерами и тестами). */
object Pcm {
    const val TARGET_RATE: Int = 16_000

    /** Interleaved PCM16 -> моно float [-1, 1) (среднее по каналам). */
    fun shortsToMono(interleaved: ShortArray, length: Int, channels: Int): FloatArray {
        require(channels >= 1) { "channels=$channels" }
        val frames = length / channels
        val out = FloatArray(frames)
        if (channels == 1) {
            for (i in 0 until frames) out[i] = interleaved[i] / 32768f
        } else {
            val inv = 1f / (32768f * channels)
            for (i in 0 until frames) {
                var s = 0
                val b = i * channels
                for (c in 0 until channels) s += interleaved[b + c]
                out[i] = s * inv
            }
        }
        return out
    }

    /** Interleaved float -> моно (среднее по каналам). */
    fun floatsToMono(interleaved: FloatArray, length: Int, channels: Int): FloatArray {
        val frames = length / channels
        return FloatArray(frames) { i ->
            var s = 0f
            val b = i * channels
            for (c in 0 until channels) s += interleaved[b + c]
            s / channels
        }
    }

    /** float -> PCM16 с насыщением. */
    fun floatsToShorts(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i -> (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }

    /** Приводит произвольный моно-сигнал к 16 кГц с антиалиасингом. */
    fun to16k(samples: FloatArray, sampleRate: Int): AudioBuffer =
        AudioBuffer(Resampler.resample(samples, sampleRate, TARGET_RATE), TARGET_RATE)

    /** Убирает NaN/Inf и зажимает амплитуду в [-1, 1]: защита движков от мусорных данных кодека. */
    fun sanitize(samples: FloatArray) {
        for (i in samples.indices) {
            val v = samples[i]
            samples[i] = if (v.isNaN()) 0f else v.coerceIn(-1f, 1f)
        }
    }
}
