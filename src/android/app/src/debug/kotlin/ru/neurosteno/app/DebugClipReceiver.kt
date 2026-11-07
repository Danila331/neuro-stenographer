package ru.neurosteno.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** Debug-сборка: `adb shell am broadcast -a ru.neurosteno.app.DEBUG_CLIP --es text "..."` + `input keyevent KEYCODE_PASTE`. */
class DebugClipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("debug", text))
    }
}
