package ru.neurosteno.app.models.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import ru.neurosteno.app.NeuroApp
import ru.neurosteno.app.R
import ru.neurosteno.core.models.InsufficientSpaceException

/**
 * Загрузка набора моделей в WorkManager (запускается только действием пользователя). Прогресс -- через `setProgress`
 * и уведомление; при отмене частично скачанное остаётся в `.dl-<id>` -- следующий запуск докачивает.
 */
class ModelDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val setId = inputData.getString(KEY_SET_ID) ?: return Result.failure(error("Не указан набор моделей"))
        val graph = (applicationContext as NeuroApp).graph
        val set = graph.catalog.set(setId) ?: return Result.failure(error("Набора «$setId» нет в каталоге"))
        val installer = SetInstaller(graph.modelStore, graph.catalog)
        val job = currentCoroutineContext().job
        var lastPublish = 0L
        runCatching { setForeground(foregroundInfo(set.title, 0, 0)) }
        return withContext(Dispatchers.IO) {
            try {
                installer.install(set, isCancelled = { !job.isActive }) { done, total, file ->
                    val now = System.currentTimeMillis()
                    if (now - lastPublish >= 700 || done >= total) {
                        lastPublish = now
                        ModelDownloadProgress.publish(setId, done, total, file)
                        notify(set.title, done, total)
                    }
                }
                ModelDownloadProgress.clear(setId)
                graph.onModelSetInstalled(setId)
                Result.success()
            } catch (e: DownloadCancelledException) {
                ModelDownloadProgress.clear(setId)
                Result.failure(error("Загрузка отменена"))
            } catch (e: InsufficientSpaceException) {
                ModelDownloadProgress.clear(setId)
                Result.failure(error("Недостаточно места: нужно ещё ${(e.neededBytes - e.availableBytes) / (1024 * 1024)} МБ"))
            } catch (e: DownloadIntegrityException) {
                installer.discardPartial(setId)
                ModelDownloadProgress.clear(setId)
                Result.failure(error(e.message ?: "Файл повреждён"))
            } catch (e: java.io.IOException) {
                ModelDownloadProgress.clear(setId)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(error(e.message ?: "Ошибка сети"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                ModelDownloadProgress.clear(setId)
                throw e
            } catch (e: Throwable) {
                ModelDownloadProgress.clear(setId)
                android.util.Log.e("Neurosteno", "model download failed", e)
                Result.failure(error("Ошибка загрузки: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    private fun error(message: String): Data = workDataOf(KEY_ERROR to message)

    private fun channel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.notification_channel_download), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun foregroundInfo(title: String, done: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channel(nm)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.notification_download_title, title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTIFICATION_ID, n, type)
    }

    private var lastPercent = -1
    /** Не чаще 2 обновлений в секунду: быстрая сеть меняет процент десятки раз в секунду, а система такие обновления отбрасывает. */
    private val gate = ru.neurosteno.app.util.MinIntervalGate(500)

    private fun notify(title: String, done: Long, total: Long) {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        if (percent == lastPercent || !gate.tryPass()) return
        lastPercent = percent
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, foregroundInfo(title, done, total).notification)
        }
    }

    companion object {
        const val KEY_SET_ID = "set_id"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 2
        private const val MAX_ATTEMPTS = 3

        fun uniqueName(setId: String) = "model-download-$setId"
    }
}
