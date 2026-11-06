package ru.neurosteno.app.models

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.BuildConfig
import ru.neurosteno.app.ProcessingState
import ru.neurosteno.app.ProcessingUi
import ru.neurosteno.app.models.download.ModelDownloadProgress
import ru.neurosteno.app.models.download.ModelDownloadWorker
import ru.neurosteno.app.models.download.SetInstaller
import ru.neurosteno.core.models.SetState

/** Что происходит с загрузкой набора. */
sealed interface DownloadUi {
    data object Idle : DownloadUi
    data object Queued : DownloadUi
    data class Running(val doneBytes: Long, val totalBytes: Long, val file: String, val speedBps: Long) : DownloadUi
    /** Остановлена (отмена/нет сети/перезапуск): [partialBytes] уже на диске, можно продолжить. */
    data class Paused(val partialBytes: Long) : DownloadUi
    data class Failed(val message: String, val partialBytes: Long) : DownloadUi
}

data class ModelSetRow(
    val id: String,
    val title: String,
    val description: String,
    val tier: String,
    /** Размер набора на диске после установки. */
    val sizeBytes: Long,
    /** Сколько нужно скачать (0 -- набор нельзя скачать: его нет в каталоге). */
    val downloadBytes: Long,
    val installed: Boolean,
    val incomplete: Boolean,
    val active: Boolean,
    val builtin: Boolean,
    val inCatalog: Boolean,
    val recommended: Boolean,
    val download: DownloadUi,
)

/** Менеджер моделей для экрана «Модели»: наборы (установленные и из каталога), загрузка, выбор активного, удаление/восстановление. */
class ModelsController(private val context: Context, private val graph: AppGraph) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val work by lazy { WorkManager.getInstance(context) }
    private val tick = MutableStateFlow(0)
    private val installer by lazy { SetInstaller(graph.modelStore, graph.catalog) }

    /** Сообщение об ошибке последнего действия (удаление при идущей обработке и т. п.). */
    val lastMessage = MutableStateFlow<String?>(null)

    val rows: StateFlow<List<ModelSetRow>> by lazy {
        val infoFlows = graph.catalog.sets.map { s ->
            work.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.uniqueName(s.id)).map { s.id to it }
        }
        val works: Flow<Map<String, List<WorkInfo>>> = if (infoFlows.isEmpty()) flowOf(emptyMap()) else combine(infoFlows) { it.toMap() }
        combine(works, ModelDownloadProgress.state, tick) { w, _, _ -> build(w) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), build(emptyMap()))
    }

    fun refresh() { tick.value++ }

    private fun build(works: Map<String, List<WorkInfo>>): List<ModelSetRow> {
        val store = graph.modelStore
        val catalog = graph.catalog
        val active = store.activeSetId?.takeIf { store.state(it) == SetState.COMPLETE }
        val progress = ModelDownloadProgress.state.value
        val installed = store.listSets().associateBy { it.id }
        val ids = (catalog.sets.map { it.id } + installed.keys).distinct()
        return ids.map { id ->
            val cat = catalog.set(id)
            val manifest = installed[id]
            val state = if (manifest != null) store.state(id) else SetState.MISSING
            val isBuiltin = manifest?.builtin == true || (id == graph.bundledSetId && BuildConfig.BUNDLED_MODELS && manifest == null)
            val dl = downloadState(id, works[id].orEmpty(), progress[id])
            ModelSetRow(
                id = id,
                title = cat?.title ?: manifest?.id ?: id,
                description = cat?.description ?: "",
                tier = cat?.tier ?: manifest?.tier ?: "",
                sizeBytes = manifest?.totalBytes ?: cat?.let { catalog.installedBytes(it) } ?: 0L,
                downloadBytes = cat?.let { catalog.downloadBytes(it) } ?: 0L,
                installed = state == SetState.COMPLETE,
                incomplete = state == SetState.INCOMPLETE,
                active = id == active,
                builtin = isBuiltin,
                inCatalog = cat != null,
                recommended = cat?.recommended == true,
                download = dl,
            )
        }.sortedWith(compareByDescending<ModelSetRow> { it.active }.thenByDescending { it.installed }.thenBy { it.id })
    }

    private fun downloadState(id: String, infos: List<WorkInfo>, p: ModelDownloadProgress.Item?): DownloadUi {
        val info = infos.firstOrNull()
        val partial = installer.partialBytes(id)
        return when (info?.state) {
            WorkInfo.State.RUNNING ->
                if (p != null) DownloadUi.Running(p.doneBytes, p.totalBytes, p.file, p.speedBps) else DownloadUi.Running(partial, graph.catalog.set(id)?.let { graph.catalog.downloadBytes(it) } ?: 0, "", 0)
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadUi.Queued
            WorkInfo.State.FAILED -> DownloadUi.Failed(info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Не удалось скачать набор", partial)
            WorkInfo.State.CANCELLED -> if (partial > 0) DownloadUi.Paused(partial) else DownloadUi.Idle
            else -> if (partial > 0 && graph.modelStore.state(id) != SetState.COMPLETE) DownloadUi.Paused(partial) else DownloadUi.Idle
        }
    }

    // ---- действия ----

    fun startDownload(setId: String) {
        val set = graph.catalog.set(setId) ?: return
        lastMessage.value = null
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_SET_ID to set.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(ModelDownloadWorker.uniqueName(set.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelDownload(setId: String) {
        work.cancelUniqueWork(ModelDownloadWorker.uniqueName(setId))
        scope.launch { kotlinx.coroutines.delay(300); refresh() }
    }

    /** Удаляет недокачанное (отмена с освобождением места). */
    fun discardPartial(setId: String) {
        work.cancelUniqueWork(ModelDownloadWorker.uniqueName(setId))
        installer.discardPartial(setId)
        work.pruneWork()
        refresh()
    }

    fun activate(setId: String) {
        if (busy()) { lastMessage.value = "Дождитесь окончания обработки: сейчас набор моделей менять нельзя."; return }
        val store = graph.modelStore
        if (store.state(setId) != SetState.COMPLETE) return
        store.activeSetId = setId
        ProcessingState.setModels(ModelsStatus.Ready(setId))
        refresh()
    }

    /** Удаляет установленный набор. Удаление встроенного запоминается, чтобы он не вернулся сам при следующем запуске. */
    fun delete(setId: String) {
        if (busy()) { lastMessage.value = "Дождитесь окончания обработки: сейчас набор моделей удалять нельзя."; return }
        val store = graph.modelStore
        store.delete(setId)
        if (setId == graph.bundledSetId) graph.settings.update { it.copy(builtinDeleted = true) }
        val active = store.activeSetId?.takeIf { store.state(it) == SetState.COMPLETE }
        ProcessingState.setModels(
            if (active != null) ModelsStatus.Ready(active)
            else ModelsStatus.Missing("Набор моделей удалён. Скачайте набор или восстановите встроенный в разделе «Модели»."),
        )
        refresh()
    }

    fun restoreBuiltin() {
        if (busy()) { lastMessage.value = "Дождитесь окончания обработки."; return }
        scope.launch {
            ModelBootstrap.restoreBuiltin(graph) { ProcessingState.setModels(it) }
            refresh()
        }
    }

    fun clearMessage() { lastMessage.value = null }

    private fun busy() = ProcessingState.processing.value is ProcessingUi.Running
}
