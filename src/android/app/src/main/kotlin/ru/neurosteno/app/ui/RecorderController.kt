package ru.neurosteno.app.ui

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.neurosteno.app.ProcessingService
import ru.neurosteno.app.live.LiveController
import ru.neurosteno.audio.AudioRecorder

data class RecorderUi(
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    /** Уровень сигнала 0..1 для индикатора. */
    val level: Float = 0f,
    val error: String? = null,
)

/** Запись с микрофона в WAV 16 кГц (приватная папка приложения). Живёт в ViewModel: поворот экрана запись не прерывает. */
class RecorderController(private val context: Context, private val live: LiveController? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: AudioRecorder? = null
    private var file: File? = null
    private var ticker: Job? = null
    @Volatile private var level = 0f

    private val _ui = MutableStateFlow(RecorderUi())
    val ui: StateFlow<RecorderUi> = _ui.asStateFlow()

    fun start() {
        if (recorder != null) return
        val dir = File(context.filesDir, ProcessingService.RECORDINGS_DIR).apply { mkdirs() }
        val name = "Запись " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date()) + ".wav"
        val f = File(dir, name)
        // живая расшифровка (B13): может быть выключена (нет модели/памяти) -- тогда onPcm == null и запись идёт как раньше
        val onPcm = live?.begin()
        val r = AudioRecorder(f, onLevel = { level = it }, onPcm = onPcm)
        try {
            r.start()
        } catch (e: Exception) {
            live?.cancel()
            _ui.value = RecorderUi(error = "Не удалось начать запись: ${e.message ?: "микрофон недоступен"}")
            return
        }
        recorder = r
        file = f
        _ui.value = RecorderUi(recording = true)
        ticker = scope.launch {
            while (isActive) {
                _ui.value = _ui.value.copy(elapsedMs = r.durationMs, level = level)
                level *= 0.6f
                delay(120)
            }
        }
    }

    /** Останавливает запись и возвращает файл (null при ошибке или слишком короткой записи). */
    fun stop(): File? {
        val r = recorder ?: return null
        ticker?.cancel()
        recorder = null
        val f = file
        file = null
        return try {
            r.stop()
            live?.end() // дорасшифровать хвост и ВЫГРУЗИТЬ потоковую модель до старта полной обработки
            val ok = f != null && r.durationMs >= MIN_MS
            if (!ok) f?.delete()
            _ui.value = RecorderUi(error = if (ok) null else "Запись слишком короткая")
            if (ok) f else null
        } catch (e: Exception) {
            live?.cancel()
            f?.delete()
            _ui.value = RecorderUi(error = "Запись прервана: ${e.message}")
            null
        }
    }

    fun cancel() {
        val r = recorder ?: return
        ticker?.cancel()
        recorder = null
        runCatching { r.stop() }
        live?.cancel()
        file?.delete()
        file = null
        _ui.value = RecorderUi()
    }

    fun clearError() { _ui.value = _ui.value.copy(error = null) }

    private companion object {
        const val MIN_MS = 1_000L
    }
}
