package ru.neurosteno.app.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.neurosteno.app.AppGraph
import ru.neurosteno.core.models.InsufficientSpaceException
import ru.neurosteno.core.models.SetState

/** Состояние моделей для UI. */
sealed interface ModelsStatus {
    data object Checking : ModelsStatus
    data class Installing(val fraction: Float) : ModelsStatus
    data class Ready(val setId: String) : ModelsStatus
    /** Моделей нет (online без загрузки, повреждённые assets, удалён встроенный набор) -- понятное сообщение, не падение. */
    data class Missing(val message: String) : ModelsStatus
}

/**
 * Первый запуск: копирует встроенный набор из assets в `filesDir/models/<set-id>` (llama.cpp и sherpa-onnx требуют путь
 * к файлу). Повторные запуски -- только быстрая проверка размеров. Если пользователь сам удалил встроенный набор
 * ([restoreBuiltin] вернёт его), автоматически набор не восстанавливается.
 */
object ModelBootstrap {
    /** Активность и сервис могут вызвать подготовку одновременно -- установка набора строго по одной. */
    private val lock = Mutex()

    suspend fun ensureInstalled(graph: AppGraph, onStatus: (ModelsStatus) -> Unit): ModelsStatus =
        run(graph, force = false, onStatus)

    /** «Восстановить из APK»: копирует встроенный набор заново и делает его активным. */
    suspend fun restoreBuiltin(graph: AppGraph, onStatus: (ModelsStatus) -> Unit): ModelsStatus {
        graph.settings.update { it.copy(builtinDeleted = false) }
        return run(graph, force = true, onStatus)
    }

    private suspend fun run(graph: AppGraph, force: Boolean, onStatus: (ModelsStatus) -> Unit): ModelsStatus = lock.withLock {
        withContext(Dispatchers.IO) {
            val store = graph.modelStore
            val setId = graph.bundledSetId
            val result = try {
                when {
                    setId == null -> activeOrMissing(graph, "В этом варианте нет встроенных моделей.")
                    !force && graph.settings.current.builtinDeleted -> activeOrMissing(graph, "Встроенный набор моделей удалён.")
                    // уже установлен ТОТ ЖЕ набор (манифест совпал с встроенным -- после обновления APK модели могут смениться)
                    !force && store.state(setId) == SetState.COMPLETE && store.activeSetId != null &&
                        store.state(store.activeSetId!!) == SetState.COMPLETE &&
                        (store.activeSetId != setId || store.readManifest(store.setDir(setId)) == graph.bundledSource()?.manifest()) ->
                        ModelsStatus.Ready(store.activeSetId!!)
                    else -> {
                        onStatus(ModelsStatus.Installing(0f))
                        val source = graph.bundledSource()!!
                        store.install(source) { done, total ->
                            onStatus(ModelsStatus.Installing(if (total > 0) done.toFloat() / total else 0f))
                        }
                        store.activeSetId = setId
                        ModelsStatus.Ready(setId)
                    }
                }
            } catch (e: InsufficientSpaceException) {
                ModelsStatus.Missing("Недостаточно места для моделей: нужно ещё ${(e.neededBytes - e.availableBytes) / (1024 * 1024)} МБ.")
            } catch (e: Exception) {
                ModelsStatus.Missing("Не удалось подготовить модели: ${e.message ?: e.javaClass.simpleName}")
            }
            onStatus(result)
            result
        }
    }

    private fun activeOrMissing(graph: AppGraph, message: String): ModelsStatus {
        val id = graph.modelStore.activeSetId
        return if (id != null && graph.modelStore.state(id) == SetState.COMPLETE) ModelsStatus.Ready(id)
        else ModelsStatus.Missing("$message Откройте раздел «Модели», чтобы скачать набор или восстановить встроенный.")
    }
}
