package ru.neurosteno.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder
import ru.neurosteno.core.AudioBuffer
import ru.neurosteno.core.AudioDecoder
import ru.neurosteno.core.AudioInput
import ru.neurosteno.core.FileAudioInput
import ru.neurosteno.core.ProgressListener
import ru.neurosteno.core.Stage
import ru.neurosteno.core.audio.Pcm
import ru.neurosteno.core.audio.PcmAccumulator
import ru.neurosteno.core.audio.StreamBytes
import ru.neurosteno.core.audio.ResamplerStream
import ru.neurosteno.core.audio.WavDecoder

/** Источник аудио из SAF/`content://`/`file://`. */
data class UriAudioInput(val uri: Uri, override val displayName: String = uri.lastPathSegment ?: "audio") : AudioInput

/**
 * Декодер любых форматов, которые умеет платформа (MP3, AAC/M4A, OGG/Opus, FLAC, AMR, WAV, 3GP, MP4/MKV с аудиодорожкой):
 * MediaExtractor + MediaCodec -> PCM -> моно (среднее по каналам) -> 16 кГц потоковым windowed-sinc ресемплером
 * (антиалиасинг, без линейной интерполяции). Запись целиком в исходной частоте в памяти не хранится.
 *
 * Устойчивость: WAV разбирается чистым Kotlin ([WavDecoder]) как быстрый путь; если платформа не справилась,
 * для WAV включается этот же запасной путь; мусорные значения (NaN/Inf, выход за [-1, 1]) зачищаются.
 * Любая ошибка оборачивается в [IOException] с понятным текстом. Прогресс -- по `sampleTime` экстрактора;
 * отмена -- через бросающий [ProgressListener] (вызывается каждые несколько буферов).
 */
class MediaCodecAudioDecoder(private val context: Context) : AudioDecoder {

    override fun decode(input: AudioInput, progress: ProgressListener): AudioBuffer {
        progress.onProgress(Stage.DECODE, 0f)
        val result = try {
            decodeWavFast(input) ?: decodeWithCodec(input, progress)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e // отмена -- не ошибка декодирования
        } catch (e: IOException) {
            // платформа не смогла -- последний шанс для WAV, остальное -- понятная ошибка
            decodeWavFast(input, force = true) ?: throw e
        } catch (e: RuntimeException) {
            decodeWavFast(input, force = true)
                ?: throw IOException("Не удалось декодировать файл (${input.displayName}): ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (result.samples.isEmpty()) throw IOException("В файле нет аудио (${input.displayName})")
        Pcm.sanitize(result.samples)
        progress.onProgress(Stage.DECODE, 1f)
        return result
    }

    // ---- WAV fast path ----

    private fun decodeWavFast(input: AudioInput, force: Boolean = false): AudioBuffer? {
        val stream = openStream(input) ?: return null
        return stream.use { s ->
            val head = ByteArray(12)
            var n = 0
            while (n < head.size) {
                val r = s.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            if (n < 12 || !WavDecoder.isWav(head)) return null
            // весь файл в ОДИН массив (раньше: readBytes + head + rest = две копии); огромный WAV -- потоково через кодек
            val all = StreamBytes.readAll(head, s, if (force) Int.MAX_VALUE - 16 else MAX_WAV_BYTES) ?: return null
            try {
                WavDecoder.decode(all)
            } catch (e: IllegalArgumentException) {
                null // экзотический формат -- пусть попробует платформа
            }
        }
    }

    private fun openStream(input: AudioInput) = when (input) {
        is UriAudioInput -> runCatching { context.contentResolver.openInputStream(input.uri) }.getOrNull()
        is FileAudioInput -> runCatching { java.io.File(input.path).inputStream() }.getOrNull()
        else -> null
    }

    // ---- MediaExtractor + MediaCodec ----

    private fun decodeWithCodec(input: AudioInput, progress: ProgressListener): AudioBuffer {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var sink: PcmSink? = null
        try {
            when (input) {
                is UriAudioInput -> extractor.setDataSource(context, input.uri, null)
                is FileAudioInput -> extractor.setDataSource(input.path)
                else -> throw IOException("Неподдерживаемый источник: ${input.displayName}")
            }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("В файле нет аудиодорожки (${input.displayName})")
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            sink = PcmSink(
                sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100),
                channels = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1),
                acc = PcmAccumulator(MAX_SAMPLES.toLong(), spillDir = context.cacheDir),
            )

            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                readRaw(extractor, format, sink, durationUs, progress)
                return sink.finish()
            }

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            var tick = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        idle = 0
                        if (info.size > 0) {
                            val out = c.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            sink.accept(out, sink.encodingOf(c.outputFormat))
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (++tick % 8 == 0 && durationUs > 0) {
                            progress.onProgress(Stage.DECODE, (extractor.sampleTime.coerceAtLeast(0) / durationUs.toFloat()).coerceIn(0f, 0.99f))
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onFormat(c.outputFormat)
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // защита от зависшего кодека: ~10 с без прогресса -- сдаёмся с ошибкой, а не висим вечно
                        if (inputDone && ++idle > MAX_IDLE_LOOPS) throw IOException("Кодек не отвечает (${input.displayName})")
                        progress.onProgress(Stage.DECODE, 0f) // точка проверки отмены
                    }
                }
            }
            return sink.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { sink?.close() } // временный файл PCM удаляется и при отмене/ошибке
        }
    }

    /** `audio/raw`: данные уже PCM, кодек не нужен. */
    private fun readRaw(extractor: MediaExtractor, format: MediaFormat, sink: PcmSink, durationUs: Long, progress: ProgressListener) {
        val enc = sink.encodingOf(format)
        val buf = java.nio.ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.nativeOrder())
        var tick = 0
        while (true) {
            buf.clear()
            val n = extractor.readSampleData(buf, 0)
            if (n < 0) break
            buf.limit(n)
            buf.position(0)
            sink.accept(buf, enc)
            extractor.advance()
            if (++tick % 32 == 0) {
                progress.onProgress(Stage.DECODE, if (durationUs > 0) (extractor.sampleTime.coerceAtLeast(0) / durationUs.toFloat()).coerceIn(0f, 0.99f) else 0f)
            }
        }
    }

    /**
     * Приёмник PCM: interleaved 16-bit/float -> моно -> потоковый ресемплер -> накопление 16 кГц.
     * Формат (частота/каналы) может измениться в ходе декодирования (INFO_OUTPUT_FORMAT_CHANGED) -- пересоздаём ресемплер.
     */
    private class PcmSink(sampleRate: Int, channels: Int, private val acc: PcmAccumulator) {
        private var rate = sampleRate
        private var ch = channels
        private var resampler = ResamplerStream(rate, Pcm.TARGET_RATE)
        private var shorts = ShortArray(0)
        private var floats = FloatArray(0)

        fun onFormat(f: MediaFormat) {
            val newRate = f.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, rate)
            val newCh = f.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, ch)
            if (newRate != rate) {
                append(resampler.finish())
                rate = newRate
                resampler = ResamplerStream(rate, Pcm.TARGET_RATE)
            }
            ch = newCh
        }

        fun encodingOf(f: MediaFormat): Int =
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else ENCODING_PCM_16BIT

        fun accept(buffer: java.nio.ByteBuffer, encoding: Int) {
            buffer.order(ByteOrder.nativeOrder())
            val mono: FloatArray
            if (encoding == ENCODING_PCM_FLOAT) {
                val n = buffer.remaining() / 4
                if (floats.size < n) floats = FloatArray(n)
                buffer.asFloatBuffer().get(floats, 0, n)
                mono = Pcm.floatsToMono(floats, n, ch)
            } else {
                val n = buffer.remaining() / 2
                if (shorts.size < n) shorts = ShortArray(n)
                buffer.asShortBuffer().get(shorts, 0, n)
                mono = Pcm.shortsToMono(shorts, n, ch)
            }
            Pcm.sanitize(mono)
            append(resampler.push(mono))
        }

        /** Один массив точного размера; блоки/временный файл освобождаются (раньше: удвоение массива + полная копия -- 2-3 размера записи). */
        fun finish(): AudioBuffer {
            append(resampler.finish())
            return AudioBuffer(acc.toFloatArray(), Pcm.TARGET_RATE)
        }

        fun close() = acc.close()

        private fun append(x: FloatArray) {
            if (x.isEmpty()) return
            try {
                acc.append(x)
            } catch (e: PcmAccumulator.TooLongException) {
                throw IOException("Запись слишком длинная (больше ${MAX_SAMPLES / Pcm.TARGET_RATE / 3600} ч)", e)
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_LOOPS = 1_000
        const val MAX_WAV_BYTES = 200 * 1024 * 1024
        const val MAX_SAMPLES = 16_000 * 3600 * 3 // 3 часа
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4

        fun MediaFormat.intOrDefault(key: String, def: Int): Int = if (containsKey(key)) getInteger(key) else def
    }
}
