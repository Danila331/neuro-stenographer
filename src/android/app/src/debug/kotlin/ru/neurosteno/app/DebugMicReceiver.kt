package ru.neurosteno.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import ru.neurosteno.audio.DebugAudio
import ru.neurosteno.audio.WavFilePcmSource

/**
 * Только debug (B13): подаёт WAV 16 кГц в «Запись» вместо микрофона (на эмуляторе нет звукового ввода).
 * `adb shell am broadcast -a ru.neurosteno.app.DEBUG_MIC_WAV -p <pkg> --es path /sdcard/Android/data/<pkg>/files/x.wav [--ez realtime false]`;
 * без `path` -- снова обычный микрофон.
 */
class DebugMicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path")
        val realtime = intent.getBooleanExtra("realtime", true)
        DebugAudio.sourceFactory = if (path.isNullOrBlank()) null else ({ WavFilePcmSource(File(path), realtime) })
    }
}
