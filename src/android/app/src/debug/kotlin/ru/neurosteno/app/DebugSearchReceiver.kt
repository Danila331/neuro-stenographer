package ru.neurosteno.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ru.neurosteno.app.insight.AnswerEvent
import ru.neurosteno.app.insight.RealSearchRepository
import ru.neurosteno.app.insight.SearchScope

/**
 * Только debug (скрипты смоука AI-поиска, B51): запрос без интерфейса. Cyrillic через base64, т. к. `adb shell am` не передаёт UTF-8:
 *   adb shell am broadcast -a ru.neurosteno.app.DEBUG_SEARCH -n ru.neurosteno.app.<tier>/ru.neurosteno.app.DebugSearchReceiver \
 *     --es q_b64 <base64(utf8)> [--es record <id записи>] [--ez answer true] [--es cmd status|release]
 * Результаты -- строки logcat `search_debug ...` (теги Neurosteno) и PSS процесса после запроса.
 */
class DebugSearchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as NeuroApp).graph
        val pending = goAsync()
        scope.launch {
            try {
                val cmd = intent.getStringExtra("cmd")
                if (cmd == "status") {
                    val s = graph.search.engine()
                    Log.i(TAG, "search_debug status indexed=${s?.indexedRecords()?.size} missingVectors=${s?.missingVectors()?.size} store=${graph.search.storeInfo()} " +
                        "progress=${graph.search.progress.value.size} embedderLoaded=${graph.search.embedder.isLoaded} pss_mb=${pssMb()}")
                    return@launch
                }
                if (cmd == "release") { graph.search.embedder.release(); Log.i(TAG, "search_debug released pss_mb=${pssMb()}"); return@launch }
                val q = String(Base64.decode(intent.getStringExtra("q_b64") ?: return@launch, Base64.DEFAULT), Charsets.UTF_8)
                val record = intent.getStringExtra("record")
                val repo = RealSearchRepository(graph)
                val pss0 = pssMb()
                val t0 = System.nanoTime()
                val hits = repo.search(q, if (record != null) SearchScope.RECORD else SearchScope.HISTORY, record)
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "search_debug q='$q' scope=${if (record != null) "record" else "history"} hits=${hits.size} total_ms=$ms pss_before_mb=$pss0 pss_after_mb=${pssMb()}")
                hits.forEachIndexed { i, h ->
                    Log.i(TAG, "search_debug hit#$i rec=${h.recordId} t=${h.startMs / 1000}s score=${"%.4f".format(h.score)} :: " + h.lines.joinToString(" | ") { "[${it.speakerName}] ${it.text.take(60)}" })
                }
                if (intent.getBooleanExtra("answer", false) && hits.isNotEmpty()) {
                    val t1 = System.nanoTime()
                    var last = ""
                    repo.answer(q, hits).collect { ev ->
                        when (ev) {
                            is AnswerEvent.Partial -> last = ev.text
                            is AnswerEvent.Done -> Log.i(TAG, "search_debug answer_done ms=${(System.nanoTime() - t1) / 1_000_000} pss_mb=${pssMb()} :: ${ev.text}")
                            is AnswerEvent.Failed -> Log.i(TAG, "search_debug answer_failed :: ${ev.message} (partial='$last')")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "search_debug failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun pssMb(): Int = (Debug.getPss() / 1024L).toInt()

    private companion object {
        const val TAG = "Neurosteno"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
