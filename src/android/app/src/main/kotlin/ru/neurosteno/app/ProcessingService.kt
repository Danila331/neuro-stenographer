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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.neurosteno.app.models.ModelBootstrap
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.app.pipeline.ModelsNotReadyException
import ru.neurosteno.app.pipeline.PipelineRunner
import ru.neurosteno.core.PipelineException

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
                job?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri = intent.data
                if (uri == null) {
                    ProcessingState.setProcessing(ProcessingUi.Failed("Не передан файл для обработки"))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForegroundCompat(buildNotification(0f))
                start(uri, intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment ?: "audio")
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun start(uri: Uri, name: String) {
        if (job?.isActive == true) return // уже идёт обработка
        acquireWakeLock()
        val graph = (application as NeuroApp).graph
        job = scope.launch {
            try {
                // модели могут быть ещё не скопированы (первый запуск) -- дождёмся
                val status = ProcessingState.models.value
                if (status !is ModelsStatus.Ready) {
                    val s = ModelBootstrap.ensureInstalled(graph) { ProcessingState.setModels(it) }
                    if (s !is ModelsStatus.Ready) throw ModelsNotReadyException((s as ModelsStatus.Missing).message)
                }
                val runner = PipelineRunner(applicationContext, graph)
                val result = runner.run(uri, name) { stage, stageFraction, overall ->
                    ProcessingState.setProcessing(ProcessingUi.Running(stage, stageFraction, overall))
                    updateNotification(overall)
                }
                ProcessingState.setProcessing(ProcessingUi.Done(result, name))
            } catch (e: CancellationException) {
                ProcessingState.reset()
            } catch (e: ModelsNotReadyException) {
                ProcessingState.setProcessing(ProcessingUi.Failed(e.message ?: "Модели не готовы"))
            } catch (e: PipelineException) {
                ProcessingState.setProcessing(ProcessingUi.Failed(e.message ?: "Ошибка обработки"))
            } catch (e: OutOfMemoryError) {
                ProcessingState.setProcessing(ProcessingUi.Failed("Не хватило памяти для обработки этой записи. Попробуйте файл покороче или вариант Lite."))
            } catch (e: Throwable) {
                ProcessingState.setProcessing(ProcessingUi.Failed("Не удалось обработать запись: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                releaseWakeLock()
                ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
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

    private fun buildNotification(progress: Float): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ProcessingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt(), false)
            .addAction(Notification.Action.Builder(0, getString(R.string.btn_cancel), cancel).build())
            .build()
    }

    private var lastNotifiedPercent = -1

    private fun updateNotification(progress: Float) {
        val percent = (progress * 100).toInt()
        if (percent == lastNotifiedPercent) return
        lastNotifiedPercent = percent
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(progress))
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
        const val EXTRA_NAME = "name"
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000

        fun startIntent(context: Context, uri: Uri, name: String): Intent =
            Intent(context, ProcessingService::class.java)
                .setAction(ACTION_START)
                .setData(uri)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        fun cancelIntent(context: Context): Intent =
            Intent(context, ProcessingService::class.java).setAction(ACTION_CANCEL)
    }
}
