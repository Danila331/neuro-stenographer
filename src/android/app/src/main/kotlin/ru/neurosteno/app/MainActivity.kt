package ru.neurosteno.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.neurosteno.app.models.ModelBootstrap
import ru.neurosteno.app.ui.MainScreen
import ru.neurosteno.app.ui.NeuroTheme

class MainActivity : ComponentActivity() {

    /** Файл, пришедший через «Открыть с помощью»/«Поделиться» (обрабатывается в onNewIntent тоже). */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as NeuroApp).graph
        incoming = uriFrom(intent)

        // Подготовка моделей в фоне: копирование встроенного набора из assets при первом запуске
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                if (ProcessingState.models.value !is ru.neurosteno.app.models.ModelsStatus.Ready) {
                    ModelBootstrap.ensureInstalled(graph) { ProcessingState.setModels(it) }
                }
            }
        }

        setContent {
            NeuroTheme {
                var picked by rememberSaveable { mutableStateOf<Uri?>(null) }
                val fromIntent = incoming
                if (fromIntent != null) {
                    picked = fromIntent
                    incoming = null
                }
                val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        picked = uri
                    }
                }
                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri != null) writeExport(uri)
                }
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* необязательно */ }

                MainScreen(
                    tier = BuildConfig.TIER,
                    pickedName = picked?.let { displayName(it) },
                    onPick = { openLauncher.launch(arrayOf("audio/*", "video/*", "application/ogg")) },
                    onProcess = {
                        val uri = picked ?: return@MainScreen
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) // отказ не мешает обработке
                        }
                        ProcessingState.reset()
                        ContextCompat.startForegroundService(this@MainActivity, ProcessingService.startIntent(this@MainActivity, uri, displayName(uri)))
                    },
                    onCancel = { startService(ProcessingService.cancelIntent(this@MainActivity)) },
                    onExport = { name -> exportLauncher.launch(name) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = uriFrom(intent)
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
        return uri.lastPathSegment ?: "audio"
    }

    /** Экспорт JSON строго по ТЗ -- только по явному действию пользователя, через SAF. */
    private fun writeExport(uri: Uri) {
        val done = ProcessingState.processing.value as? ProcessingUi.Done ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(done.result.json.toByteArray(Charsets.UTF_8)) }
        }.onFailure {
            ProcessingState.setProcessing(ProcessingUi.Failed("Не удалось сохранить файл: ${it.message}"))
        }
    }
}
