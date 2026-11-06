package ru.neurosteno.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.neurosteno.app.export.ExportFormat
import ru.neurosteno.app.models.ModelBootstrap
import ru.neurosteno.app.ui.AppHost
import ru.neurosteno.app.ui.AppRoot
import ru.neurosteno.app.ui.AppViewModel

/**
 * Единственная Activity. Весь интерфейс -- Compose ([AppRoot]); Activity отвечает за системные диалоги (выбор файла,
 * сохранение, разрешения), входящие intent'ы (ACTION_VIEW/SEND -- обработка стартует сама) и запуск сервиса обработки.
 * Поворот экрана не пересоздаёт Activity (configChanges в манифесте), а обработка идёт в foreground-сервисе.
 */
class MainActivity : ComponentActivity(), AppHost {

    private val vm: AppViewModel by viewModels()
    private var pendingRecordResult: ((Boolean) -> Unit)? = null

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startProcessing(uri, displayName(uri))
        }
    }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* необязательно */ }
    private val recordLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingRecordResult?.invoke(ok)
        pendingRecordResult = null
    }
    private val exportJson = exportLauncher("application/json")
    private val exportText = exportLauncher("text/plain")
    private val exportSrt = exportLauncher("application/x-subrip")
    private val exportVtt = exportLauncher("text/vtt")

    private fun exportLauncher(mime: String) = registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val pending = vm.pendingExport
        vm.pendingExport = null
        if (uri != null && pending != null) {
            val error = vm.writeExport(uri, pending.first, pending.second)
            Toast.makeText(this, error ?: "Файл сохранён", Toast.LENGTH_SHORT).show()
        }
    }

    override val appName: String get() = getString(R.string.app_name)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as NeuroApp).graph
        if (savedInstanceState == null) handleExternalIntent(intent)

        // Подготовка моделей в фоне: копирование встроенного набора из assets при первом запуске
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                if (ProcessingState.models.value !is ru.neurosteno.app.models.ModelsStatus.Ready) {
                    ModelBootstrap.ensureInstalled(graph) { ProcessingState.setModels(it) }
                }
            }
        }
        setContent { AppRoot(this, vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    /** «Открыть с помощью» / «Поделиться» / adb: обработка стартует сразу (BACKLOG B2/B3). */
    private fun handleExternalIntent(intent: Intent?) {
        val uri = uriFrom(intent) ?: return
        // повторная доставка того же intent (поворот, возврат в приложение) не должна запускать обработку заново
        if (intent?.getBooleanExtra(EXTRA_HANDLED, false) == true) return
        intent?.putExtra(EXTRA_HANDLED, true)
        startProcessing(uri, displayName(uri))
    }

    private fun uriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    }

    // ---- AppHost ----

    override fun pickAudio() {
        // audio/* -- основной тип; ogg и 3gpp/mp4 перечислены явно: часть диктофонов отдаёт их с «видео»-типом
        openLauncher.launch(arrayOf("audio/*", "application/ogg", "video/3gpp", "video/mp4"))
    }

    override fun startProcessing(uri: Uri, name: String) {
        ProcessingState.begin(name)
        ContextCompat.startForegroundService(this, ProcessingService.startIntent(this, uri, name))
    }

    override fun cancelProcessing() {
        ProcessingState.markCancelling()
        startService(ProcessingService.cancelIntent(this))
    }

    override fun export(entryId: String, format: ExportFormat) {
        val title = vm.graph.history.get(entryId)?.title ?: "transcript"
        vm.pendingExport = entryId to format
        val name = ru.neurosteno.app.export.Exports.fileName(title, format)
        when (format) {
            ExportFormat.JSON, ExportFormat.FULL_JSON -> exportJson
            ExportFormat.TXT -> exportText
            ExportFormat.SRT -> exportSrt
            ExportFormat.VTT -> exportVtt
        }.launch(name)
    }

    override fun shareText(text: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject)
        startActivity(Intent.createChooser(send, "Поделиться"))
    }

    override fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Нейро-стенографист", text))
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    override fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun requestRecordPermission(onResult: (Boolean) -> Unit) {
        pendingRecordResult = onResult
        recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun applySystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        )
    }

    override fun moveToBackground() {
        moveTaskToBack(true)
    }

    private companion object {
        const val EXTRA_HANDLED = "ru.neurosteno.app.extra.HANDLED"
    }
}
