package ru.neurosteno.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.neurosteno.app.ui.theme.AccentConfig
import ru.neurosteno.app.ui.theme.AccentVariant

/**
 * Только debug: переключает акцент на лету для скриншотов (B52).
 * `adb shell am broadcast -a ru.neurosteno.app.DEBUG_ACCENT --es accent pink` (green | pink | default).
 */
class DebugStyleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AccentConfig.override = when (intent.getStringExtra("accent")?.lowercase()) {
            "green" -> AccentVariant.GREEN
            "pink" -> AccentVariant.PINK
            else -> null
        }
    }
}
