package ru.neurosteno.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.neurosteno.app.insight.FakeInsightConfig

/**
 * Только debug (B50/B51): режимы фейков для скриншотов состояний. Поиск работает на `FakeSearchRepository` всегда (движка поиска ещё нет);
 * выжимка -- на настоящем движке, поэтому здесь управляется только поиск.
 * `adb shell am broadcast -a ru.neurosteno.app.DEBUG_INSIGHT -p <pkg> --es index building|ready --es answer normal|refuse|unavailable|disabled`
 */
class DebugInsightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("index")?.let { FakeInsightConfig.searchIndexBuilding = it == "building" }
        intent.getStringExtra("answer")?.let { FakeInsightConfig.searchAnswerMode = it }
        intent.getLongExtra("step", -1).takeIf { it >= 0 }?.let { FakeInsightConfig.searchStepMs = it }
    }
}
