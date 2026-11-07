package ru.neurosteno.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.EnumMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.data.ResultsAutosave
import ru.neurosteno.app.data.StoredTranscript
import ru.neurosteno.app.edit.FragmentApplier
import ru.neurosteno.audio.MediaCodecAudioDecoder
import ru.neurosteno.audio.UriAudioInput
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.fragment.AudioSource
import ru.neurosteno.core.fragment.FragmentRequest
import ru.neurosteno.core.fragment.FragmentResult
import ru.neurosteno.app.data.RunInfo
import ru.neurosteno.app.edit.EditableTranscript
import ru.neurosteno.app.models.ModelBootstrap
import ru.neurosteno.app.util.MinIntervalGate
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.pipeline.ModelsNotReadyException
import ru.neurosteno.app.pipeline.PipelineRunner
import ru.neurosteno.app.ui.Format
import ru.neurosteno.core.PipelineException
import ru.neurosteno.core.Stage

/**
 * Foreground Service обработки: переживает сворачивание приложения, держит CPU (partial wake lock), показывает
 * уведомление с прогрессом. Любая ошибка превращается в [ProcessingUi.Failed] -- сервис не роняет процесс.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                ProcessingState.markCancelling()
                FragmentState.markCancelling()
                job?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri = intent.data
                if (uri == null) {
                    ProcessingState.setProcessing(ProcessingUi.Failed(null, "Не передан файл для обработки"))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment ?: "audio"
                // на Android 12+ запуск foreground из фона может быть запрещён -- тогда обрабатываем как обычный сервис
                runCatching { startForegroundCompat(buildNotification(name, null, 0f)) }
                start(uri, name)
            }
            ACTION_REPROCESS -> {
                val p = FragmentParams(
                    entryId = intent.getStringExtra(EXTRA_ENTRY) ?: "",
                    startMs = intent.getLongExtra(EXTRA_START, 0), endMs = intent.getLongExtra(EXTRA_END, 0),
                    language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "auto",
                    numSpeakers = intent.getIntExtra(EXTRA_SPEAKERS, 0).takeIf { it > 0 },
                    recomputeSpeakers = intent.getBooleanExtra(EXTRA_RECOMPUTE, false),
                )
                runCatching { startForegroundCompat(buildNotification(fragmentLabel(p), null, 0f)) }
                startFragment(p)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun start(uri: Uri, name: String) {
        if (job?.isActive == true) return // уже идёт обработка
        acquireWakeLock()
        val graph = (application as NeuroApp).graph
        val startedAt = System.currentTimeMillis()
        val fractions = EnumMap<Stage, Float>(Stage::class.java)
        val resultsDir = ResultsAutosave.resultsDir(getExternalFilesDir(null), filesDir, android.os.Process.myUid())
        // маркер прошлого запуска с тем же именем не должен «залёживаться»: скрипты ждут именно свежий маркер
        ResultsAutosave.clearMarkers(resultsDir, name)
        ProcessingState.setProcessing(ProcessingUi.Running(sourceName = name, startedAtMs = startedAt))
        job = scope.launch {
            var peakPssMb = 0
            fun samplePss() {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                peakPssMb = maxOf(peakPssMb, mi.totalPss / 1024)
            }
            val sampler = launch {
                // пик PSS процесса за время обработки -- в «Статистику обработки», маркер и meta результата (B14, B33)
                while (isActive) {
                    samplePss()
                    delay(1_000)
                }
            }
            var threads = 0
            fun runInfo() = RunInfo(
                peakPssMb = peakPssMb, threads = threads, device = deviceName(), androidSdk = Build.VERSION.SDK_INT,
                totalRamMb = totalRamMb(), appVersion = BuildConfig.VERSION_NAME,
            )
            fun fail(message: String) {
                val problems = ResultsAutosave.writeErrorMarker(resultsDir, name, message, runInfo()).exceptionOrNull()
                android.util.Log.e("Neurosteno", "failed $name: $message" + (problems?.let { " (error marker: $it)" } ?: ""))
                ProcessingState.setProcessing(ProcessingUi.Failed(name, message))
            }
            try {
                val duration = withContext(Dispatchers.IO) { probeDurationMs(uri) }
                // модели могут быть ещё не скопированы (первый запуск) -- дождёмся
                val status = ProcessingState.models.value
                if (status !is ModelsStatus.Ready) {
                    val s = ModelBootstrap.ensureInstalled(graph) { ProcessingState.setModels(it) }
                    if (s !is ModelsStatus.Ready) throw ModelsNotReadyException((s as ModelsStatus.Missing).message)
                }
                val runner = PipelineRunner(applicationContext, graph)
                val outcome = runner.run(uri, name, graph.settings.current) { stage, stageFraction, overall ->
                    synchronized(fractions) { fractions[stage] = stageFraction }
                    ProcessingState.setProcessing(
                        ProcessingUi.Running(
                            sourceName = name, stage = stage, stageFraction = stageFraction, overall = overall,
                            fractions = synchronized(fractions) { HashMap(fractions) }, startedAtMs = startedAt, audioDurationMs = duration,
                        ),
                    )
                    updateNotification(name, stage, overall)
                }
                threads = outcome.threads
                val result = outcome.result
                samplePss()
                val info = runInfo()
                val modelSet = graph.modelStore.activeSetId
                // 1) результаты, 2) строка logcat (всегда, даже если запись файлов не удалась), 3) маркер -- последним
                val editable = EditableTranscript.of(result.transcript)
                val written = ResultsAutosave.write(resultsDir, name, editable, result.transcript, info)
                android.util.Log.i("Neurosteno", ResultsAutosave.doneLine(name, result.transcript, info, result.warnings, written.problems, resultsDir))
                ResultsAutosave.writeDoneMarker(resultsDir, name, result.transcript, editable.utterances.size, info, modelSet, result.warnings, written)
                    .onFailure { android.util.Log.e("Neurosteno", "done marker for $name not written", it) }
                val entry = runCatching {
                    graph.history.add(
                        sourceName = name, transcript = result.transcript, warnings = result.warnings, run = info, modelSetId = modelSet,
                        audio = { contentResolver.openInputStream(uri) ?: throw java.io.IOException("нет доступа к файлу") },
                    )
                }.onFailure { android.util.Log.e("Neurosteno", "history not saved for $name", it) }.getOrNull()
                removeOwnRecording(uri)
                if (entry != null) ProcessingState.setProcessing(ProcessingUi.Done(entry.id, name))
                else ProcessingState.setProcessing(ProcessingUi.Failed(name, "Расшифровка готова и сохранена в папку results, но не добавлена в историю (нет места?)."))
            } catch (e: CancellationException) {
                ProcessingState.reset()
            } catch (e: ModelsNotReadyException) {
                fail(e.message ?: "Модели не готовы")
            } catch (e: PipelineException) {
                android.util.Log.e("Neurosteno", "pipeline failed at ${e.stage}", e)
                fail(e.message ?: "Ошибка обработки")
            } catch (e: OutOfMemoryError) {
                fail("Не хватило памяти для обработки этой записи. Попробуйте файл покороче или вариант Lite.")
            } catch (e: Throwable) {
                android.util.Log.e("Neurosteno", "processing failed", e)
                fail("Не удалось обработать запись: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                sampler.cancel()
                releaseWakeLock()
                ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun fragmentLabel(p: FragmentParams) = "Фрагмент ${Format.clock(p.startMs)}–${Format.clock(p.endMs)}"

    /**
     * Повторная обработка фрагмента (B37) тем же foreground-сервисом: прогресс -- [FragmentState] (экран расшифровки остаётся),
     * отмена -- как у основной обработки. Результат сохраняется здесь же (история, `results/<имя>.json`, маркер), чтобы он не
     * терялся, если пользователь ушёл с экрана; предыдущая версия кладётся в `transcript.undo.json` для «Отменить».
     */
    private fun startFragment(p: FragmentParams) {
        val label = fragmentLabel(p)
        if (job?.isActive == true) {
            FragmentState.set(FragmentUi.Failed(p.entryId, "Уже идёт другая обработка. Дождитесь её окончания."))
            stopSelf()
            return
        }
        acquireWakeLock()
        val graph = (application as NeuroApp).graph
        val startedAt = System.currentTimeMillis()
        FragmentState.set(FragmentUi.Running(p.entryId, label, startedAtMs = startedAt))
        job = scope.launch {
            try {
                val entry = graph.history.get(p.entryId) ?: throw IllegalStateException("Расшифровка не найдена")
                val stored = graph.history.loadTranscript(p.entryId) ?: throw IllegalStateException("Не удалось прочитать расшифровку")
                val audioFile = graph.history.audioFile(entry)
                    ?: throw IllegalStateException("Копия аудио недоступна: пересчитать фрагмент нельзя. Обработайте файл заново.")
                val source = if (audioFile.extension.equals("wav", ignoreCase = true)) AudioSource.wav(audioFile)
                else AudioSource.decoded(UriAudioInput(Uri.fromFile(audioFile), entry.sourceName), MediaCodecAudioDecoder(applicationContext))
                val status = ProcessingState.models.value
                if (status !is ModelsStatus.Ready) throw ModelsNotReadyException("Модели не готовы: откройте раздел «Модели».")
                val request = FragmentRequest(
                    startMs = p.startMs, endMs = p.endMs, language = p.language, numSpeakers = p.numSpeakers,
                    recomputeSpeakers = p.recomputeSpeakers,
                )
                val run = PipelineRunner(applicationContext, graph).reprocessFragment(source, stored.toTranscript(), request, graph.settings.current) { stage, _, overall ->
                    FragmentState.set(FragmentUi.Running(p.entryId, label, stage, overall, startedAt))
                    updateNotification(label, stage, overall)
                }
                val r = run.result
                if (r.changed) {
                    val applied = FragmentApplier.apply(stored, r)
                    graph.history.writeUndo(p.entryId, stored)
                    graph.history.saveTranscript(p.entryId, applied.stored)
                    rewriteResults(graph, entry, applied.stored, r, p, run.threads)
                }
                android.util.Log.i("Neurosteno", "fragment $label of ${entry.sourceName}: outcome=${r.outcome} lang=${r.language} " +
                    "processing=${r.processingMs} ms stages=${r.stageMs} recompute=${p.recomputeSpeakers} warnings=${r.warnings}")
                FragmentState.set(FragmentUi.Finished(p.entryId, r.outcome, r.message, r.changed, r.added.map { TimeSpan(it.startMs, it.endMs) }, label, r.warnings, r.processingMs))
            } catch (e: CancellationException) {
                FragmentState.set(FragmentUi.Idle)
            } catch (e: ModelsNotReadyException) {
                FragmentState.set(FragmentUi.Failed(p.entryId, e.message ?: "Модели не готовы"))
            } catch (e: PipelineException) {
                android.util.Log.e("Neurosteno", "fragment failed at ${e.stage}", e)
                FragmentState.set(FragmentUi.Failed(p.entryId, e.message ?: "Ошибка обработки фрагмента"))
            } catch (e: OutOfMemoryError) {
                FragmentState.set(FragmentUi.Failed(p.entryId, "Не хватило памяти для обработки фрагмента."))
            } catch (e: Throwable) {
                android.util.Log.e("Neurosteno", "fragment failed", e)
                FragmentState.set(FragmentUi.Failed(p.entryId, "Не удалось обработать фрагмент: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                releaseWakeLock()
                ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** `results/<имя>.json`, `.full.json` и маркер `.done.json` (с блоком `fragment`) под новую расшифровку. */
    private fun rewriteResults(graph: AppGraph, entry: HistoryEntry, stored: StoredTranscript, r: FragmentResult, p: FragmentParams, threads: Int) {
        val dir = ResultsAutosave.resultsDir(getExternalFilesDir(null), filesDir, android.os.Process.myUid())
        val tr = stored.toTranscript()
        val editable = EditableTranscript.of(tr, stored.speakerNames)
        val info = entry.runInfo().let { if (threads > 0) it.copy(threads = threads) else it }
        val written = ResultsAutosave.write(dir, entry.sourceName, editable, tr, info)
        val fragment = buildJsonObject {
            put("start_ms", r.range?.startMs ?: p.startMs)
            put("end_ms", r.range?.endMs ?: p.endMs)
            put("outcome", r.outcome.name)
            put("language", r.language ?: "")
            put("recompute_speakers", p.recomputeSpeakers)
            put("processing_ms", r.processingMs)
            put("removed", r.removed.size)
            put("added", r.added.size)
        }
        ResultsAutosave.writeDoneMarker(dir, entry.sourceName, tr, editable.utterances.size, info, entry.modelSetId, entry.warnings, written, extras = mapOf("fragment" to fragment))
            .onFailure { android.util.Log.e("Neurosteno", "done marker after fragment not written", it) }
        written.problems.forEach { android.util.Log.e("Neurosteno", "fragment results write problem: $it") }
    }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").let {
            // «Google Google Pixel» у некоторых производителей модель уже содержит бренд
            if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else it
        }

    /**
     * Android 15+: система ограничивает время foreground-сервиса типа dataSync (6 ч за 24 ч) и вызывает этот метод перед остановкой.
     * Корректно прерываем обработку, а не ждём принудительной остановки (иначе ANR/краш процесса).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w("Neurosteno", "foreground service timeout (type $fgsType): processing cancelled")
        job?.cancel()
        stopSelf(startId)
    }

    /** Длительность записи для оценки оставшегося времени; null, если платформа её не сообщает. */
    private fun probeDurationMs(uri: Uri): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(applicationContext, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
            r.release()
        }
    }.getOrNull()

    private fun totalRamMb(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt()
    }

    /** Запись с микрофона лежит в приватной папке: после сохранения копии в истории оригинал не нужен. */
    private fun removeOwnRecording(uri: Uri) {
        if (uri.scheme != "file") return
        val f = File(uri.path ?: return)
        if (f.parentFile == File(filesDir, RECORDINGS_DIR)) f.delete()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- уведомление ----

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun buildNotification(name: String, stage: Stage?, progress: Float): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ProcessingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = (progress * 100).toInt()
        val text = if (stage != null) "${Format.stageTitle(stage)} · $percent%" else getString(R.string.notification_preparing)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("$name — $text")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, stage == null)
            .addAction(Notification.Action.Builder(null, getString(R.string.btn_cancel_processing), cancel).build())
            .build()
    }

    private var lastNotifiedPercent = -1

    /** Уведомление обновляется не чаще 2 раз в секунду (система отбрасывает чаще; B34). */
    private val notificationGate = MinIntervalGate(NOTIFICATION_MIN_INTERVAL_MS)

    private fun updateNotification(name: String, stage: Stage, progress: Float) {
        val percent = (progress * 100).toInt()
        if (percent == lastNotifiedPercent) return
        if (!notificationGate.tryPass()) return
        lastNotifiedPercent = percent
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(name, stage, progress))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neurosteno:processing").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "ru.neurosteno.app.action.START"
        const val ACTION_CANCEL = "ru.neurosteno.app.action.CANCEL"
        const val ACTION_REPROCESS = "ru.neurosteno.app.action.REPROCESS_FRAGMENT"
        const val EXTRA_NAME = "name"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_START = "start_ms"
        const val EXTRA_END = "end_ms"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_SPEAKERS = "num_speakers"
        const val EXTRA_RECOMPUTE = "recompute"
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_MIN_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000
        /** Подкаталог filesDir с записями с микрофона. */
        const val RECORDINGS_DIR = "recordings"

        fun startIntent(context: Context, uri: Uri, name: String): Intent =
            Intent(context, ProcessingService::class.java)
                .setAction(ACTION_START)
                .setData(uri)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        fun fragmentIntent(context: Context, p: FragmentParams): Intent =
            Intent(context, ProcessingService::class.java)
                .setAction(ACTION_REPROCESS)
                .putExtra(EXTRA_ENTRY, p.entryId).putExtra(EXTRA_START, p.startMs).putExtra(EXTRA_END, p.endMs)
                .putExtra(EXTRA_LANGUAGE, p.language).putExtra(EXTRA_SPEAKERS, p.numSpeakers ?: 0)
                .putExtra(EXTRA_RECOMPUTE, p.recomputeSpeakers)

        fun cancelIntent(context: Context): Intent =
            Intent(context, ProcessingService::class.java).setAction(ACTION_CANCEL)
    }
}
