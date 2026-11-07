package ru.neurosteno.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import ru.neurosteno.audio.MediaCodecAudioDecoder
import ru.neurosteno.core.FileAudioInput

/**
 * Только debug (B58): память декодера на длинной записи.
 *   adb shell am broadcast -a ru.neurosteno.app.DEBUG_DECODE -n <пакет>/ru.neurosteno.app.DebugDecodeReceiver --es path /sdcard/...m4a --es mode new|legacy [--ei seconds 1800]
 * `new` -- настоящий MediaCodecAudioDecoder; `legacy` -- накопление прежним алгоритмом (массив с удвоением + copyOf при finish) тем же потоком отсчётов.
 * Результат -- строка `decode_debug …` в logcat: пик PSS и пик занятой кучи Java за время работы.
 */
class DebugDecodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val mode = intent.getStringExtra("mode") ?: "new"
        val seconds = intent.getIntExtra("seconds", 1800)
        val pending = goAsync()
        thread(name = "debug-decode") {
            try {
                System.gc()
                val rt = Runtime.getRuntime()
                val before = Debug.getPss() / 1024L
                val peakPss = AtomicLong(before)
                val peakHeap = AtomicLong((rt.totalMemory() - rt.freeMemory()) shr 20)
                var run = true
                val sampler = thread(name = "debug-decode-sampler") {
                    while (run) {
                        peakPss.updateAndGet { maxOf(it, Debug.getPss() / 1024L) }
                        peakHeap.updateAndGet { maxOf(it, (rt.totalMemory() - rt.freeMemory()) shr 20) }
                        Thread.sleep(15)
                    }
                }
                val t0 = System.nanoTime()
                var samples = 0
                if (mode == "legacy") {
                    val total = 16_000 * seconds
                    var out = FloatArray(16_000 * 30)
                    var len = 0
                    val piece = FloatArray(4096) { it.toFloat() }
                    while (len < total) {
                        val n = minOf(piece.size, total - len)
                        if (len + n > out.size) out = out.copyOf(maxOf(out.size * 2, len + n))
                        System.arraycopy(piece, 0, out, len, n)
                        len += n
                    }
                    val res = out.copyOf(len) // finish: полная копия при живом out
                    samples = res.size
                } else {
                    samples = MediaCodecAudioDecoder(context.applicationContext).decode(FileAudioInput(path)) { _, _ -> }.samples.size
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                run = false
                sampler.join()
                Log.i("Neurosteno", "decode_debug mode=$mode samples=$samples ms=$ms pss_before_mb=$before pss_peak_mb=${peakPss.get()} heap_peak_mb=${peakHeap.get()}")
            } catch (e: Throwable) {
                Log.e("Neurosteno", "decode_debug failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
