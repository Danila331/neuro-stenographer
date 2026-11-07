package ru.neurosteno.app.live

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.neurosteno.app.AppGraph
import ru.neurosteno.core.MemorySnapshot
import ru.neurosteno.core.ResourcePlanner
import ru.neurosteno.core.live.LiveSession
import ru.neurosteno.core.live.LiveState
import ru.neurosteno.core.live.LiveStatus
import ru.neurosteno.core.live.StreamingAsr
import ru.neurosteno.sherpa.ModelRoot
import ru.neurosteno.sherpa.SherpaStreamingAsr

/** Что показывает экран записи про живую расшифровку. */
data class LiveUi(
    /** Превью доступно в этом варианте/наборе моделей на этом устройстве и сейчас работает или готовится. */
    val enabled: Boolean = false,
    val status: LiveStatus = LiveStatus.Loading,
    val state: LiveState = LiveState(),
    /** Почему превью выключено (память, нет модели...), для пояснения под таймером; null -- включено или не поддерживается. */
    val offReason: String? = null,
)

/**
 * Живая расшифровка при записи (B13): решает через [ResourcePlanner.planLive], можно ли включать превью, поднимает
 * [LiveSession] (рабочий поток + потоковая модель) и принимает звук от [ru.neurosteno.audio.AudioRecorder].
 * Модель живёт только между [begin] и [end]/[cancel]: полная обработка стартует уже после выгрузки.
 */
class LiveController(
    private val context: Context,
    private val graph: AppGraph,
    /** Подмена движка (тесты); по умолчанию -- [SherpaStreamingAsr] из активного набора моделей. */
    private val engineFactory: ((ru.neurosteno.core.LiveConfig, ModelRoot, Int) -> StreamingAsr)? = null,
) {
    private var session: LiveSession? = null
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    /** Начало записи. Возвращает приёмник PCM для рекордера или null, если превью выключено (запись идёт как обычно). */
    fun begin(): ((ShortArray, Int) -> Unit)? {
        release()
        val cfg = graph.activeConfig()?.first
        val dir = graph.modelStore.activeDir()
        if (cfg == null || !cfg.live.enabled || cfg.live.modelDir == null) { _ui.value = LiveUi(); return null }
        val present = dir != null && graph.modelStore.missing(listOf(cfg.live.modelDir!!)).isEmpty()
        val plan = ResourcePlanner.planLive(memorySnapshot(), cfg, Runtime.getRuntime().availableProcessors(), present)
        if (!plan.enabled || dir == null) {
            Log.i(TAG, "live: выключено (${plan.reason})")
            _ui.value = LiveUi(offReason = plan.reason?.let { "Живой текст выключен: $it. Итог после остановки будет таким же." })
            return null
        }
        Log.i(TAG, "live: включено, потоков ${plan.numThreads}, модель ${cfg.live.modelDir}")
        val root = ModelRoot(dir)
        val factory = engineFactory
        val s = LiveSession(
            factory = { factory?.invoke(cfg.live, root, plan.numThreads) ?: SherpaStreamingAsr(cfg.live, root, plan.numThreads) },
            keepPhrases = cfg.live.keepPhrases,
            onUpdate = { st, state -> _ui.value = _ui.value.copy(status = st, state = state) },
        )
        _ui.value = LiveUi(enabled = true)
        session = s
        s.start()
        return s::offer
    }

    /** Остановка записи: дорасшифровывает хвост, выгружает модель и возвращает итог превью. */
    fun end(): LiveState {
        val s = session ?: return LiveState()
        session = null
        val t0 = System.nanoTime()
        val r = s.stop()
        val st = s.stats
        Log.i(TAG, "live: звук ${st.audioMs} мс, движок ${st.busyMs} мс (RTF ${"%.3f".format(st.rtf)}), загрузка модели ${st.loadMs} мс, " +
            "фраз ${r.finals.size}, хвост и выгрузка ${(System.nanoTime() - t0) / 1_000_000} мс")
        _ui.value = LiveUi(enabled = _ui.value.enabled, status = LiveStatus.Finished, state = r)
        return r
    }

    /** Отмена записи. */
    fun cancel() {
        session?.cancel()
        session = null
        _ui.value = LiveUi()
    }

    /** Сброс состояния при уходе с экрана записи (после [end] текст нужен ещё, пока экран открыт). */
    fun reset() { if (session == null) _ui.value = LiveUi() }

    private companion object { const val TAG = "Neurosteno" }

    private fun release() { session?.cancel(); session = null }

    private fun memorySnapshot(): MemorySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemorySnapshot(totalMb = info.totalMem / (1024 * 1024), availMb = info.availMem / (1024 * 1024), lowMemory = info.lowMemory)
    }
}
