package ru.neurosteno.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

/** Состояние встроенного плеера. */
data class PlayerState(
    val available: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * Плеер поверх [MediaPlayer] для копии записи. Вызывается только с главного потока; положение обновляется раз в 100 мс,
 * пока идёт воспроизведение. Скорость применяется при каждом запуске (на старых версиях Android установка параметров
 * на остановленном плеере запускает воспроизведение).
 */
class AudioPlayer(private val scope: CoroutineScope, private val context: Context? = null) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    // ---- audio focus (B34): другое приложение/звонок забирает звук -> пауза, а не тихое глушение системой ----
    private val audioManager: AudioManager? = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnGain = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (_state.value.playing) { resumeOnGain = true; doPause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { player?.setVolume(DUCK_VOLUME, DUCK_VOLUME) }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { player?.setVolume(1f, 1f) }
                if (resumeOnGain) { resumeOnGain = false; play() }
            }
        }
    }

    /** Наушники отключили -- пауза, чтобы речь не зазвучала из динамика. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) { if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }
    private var noisyRegistered = false

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true // без контекста (тесты) фокус не нужен
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build().also { focusRequest = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager?.abandonAudioFocusRequest(it) } }
        unregisterNoisy()
    }

    private fun registerNoisy() {
        val c = context ?: return
        if (noisyRegistered) return
        ContextCompat.registerReceiver(c, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        val c = context ?: return
        if (noisyRegistered) { runCatching { c.unregisterReceiver(noisyReceiver) }; noisyRegistered = false }
    }
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun load(file: File?, knownDurationMs: Long) {
        release()
        if (file == null || !file.isFile) {
            _state.value = PlayerState(available = false, durationMs = knownDurationMs)
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                stopTicker()
                resumeOnGain = false
                abandonFocus()
                _state.value = _state.value.copy(playing = false, positionMs = _state.value.durationMs)
            }
            mp.setOnErrorListener { _, what, extra ->
                stopTicker()
                _state.value = _state.value.copy(playing = false, error = "Ошибка воспроизведения ($what/$extra)")
                true
            }
            mp.prepare()
            player = mp
            _state.value = PlayerState(available = true, durationMs = mp.duration.toLong().takeIf { it > 0 } ?: knownDurationMs, speed = _state.value.speed)
        } catch (e: Exception) {
            runCatching { mp.release() }
            _state.value = PlayerState(available = false, durationMs = knownDurationMs, error = "Не удалось открыть запись для воспроизведения")
        }
    }

    fun toggle() { if (_state.value.playing) pause() else play() }

    fun play() {
        val mp = player ?: return
        if (!requestFocus()) {
            _state.value = _state.value.copy(error = "Звук сейчас занят другим приложением")
            return
        }
        registerNoisy()
        runCatching {
            if (_state.value.positionMs >= _state.value.durationMs - 50) mp.seekTo(0)
            mp.start()
            applySpeed(mp, _state.value.speed)
            _state.value = _state.value.copy(playing = true, error = null)
            startTicker()
        }
    }

    /** Пауза по действию пользователя, при уходе в фон и т. п.: фокус отдаём, автопродолжения нет. */
    fun pause() {
        resumeOnGain = false
        doPause()
    }

    private fun doPause() {
        val mp = player ?: return
        runCatching { if (mp.isPlaying) mp.pause() }
        stopTicker()
        if (!resumeOnGain) abandonFocus() // при временной потере фокуса оставляем запрос, чтобы продолжить по AUDIOFOCUS_GAIN
        _state.value = _state.value.copy(playing = false, positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(_state.value.positionMs))
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        val target = ms.coerceIn(0, maxOf(0, _state.value.durationMs - 1))
        runCatching { mp.seekTo(target.toInt()) }
        _state.value = _state.value.copy(positionMs = target)
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    /** Перемотка к [ms] и воспроизведение (тап по реплике). */
    fun playFrom(ms: Long) {
        seekTo(ms)
        if (!_state.value.playing) play()
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        val mp = player ?: return
        if (_state.value.playing) applySpeed(mp, speed)
    }

    fun release() {
        stopTicker()
        resumeOnGain = false
        abandonFocus()
        player?.let { runCatching { it.release() } }
        player = null
        _state.value = _state.value.copy(available = false, playing = false)
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                val pos = runCatching { mp.currentPosition.toLong() }.getOrNull() ?: break
                _state.value = _state.value.copy(positionMs = pos)
                delay(100)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    companion object {
        private const val DUCK_VOLUME = 0.25f
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
