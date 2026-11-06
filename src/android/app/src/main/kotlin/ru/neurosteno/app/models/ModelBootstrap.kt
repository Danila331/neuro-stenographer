package ru.neurosteno.app.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.neurosteno.app.AppGraph
import ru.neurosteno.core.models.InsufficientSpaceException
import ru.neurosteno.core.models.SetState

/** Состояние моделей для UI. */
sealed interface ModelsStatus {
    data object Checking : ModelsStatus
    data class Installing(val fraction: Float) : ModelsStatus
    data class Ready(val setId: String) : ModelsStatus
    /** Моделей нет (online без загрузки, повреждённые assets) -- понятное сообщение, не падение. */
    data class Missing(val message: String) : ModelsStatus
}

/**
 * Первый запуск: копирует встроенный набор из assets в `filesDir/models/<set-id>` (llama.cpp и sherpa-onnx требуют путь
 * к файлу). Повторные запуски -- только быстрая проверка размеров. Удалить набор и восстановить его из assets позже
 * можно теми же [ModelStore.delete] / [ensureInstalled] (экран «Модели», волна 2).
 */
object ModelBootstrap {

    suspend fun ensureInstalled(graph: AppGraph, onStatus: (ModelsStatus) -> Unit): ModelsStatus = withContext(Dispatchers.IO) {
        val store = graph.modelStore
        val setId = graph.bundledSetId
        val result = try {
            when {
                setId == null -> activeOrMissing(graph, "В этом варианте нет встроенных моделей.")
                store.state(setId) == SetState.COMPLETE && store.activeSetId != null -> ModelsStatus.Ready(store.activeSetId!!)
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

    private fun activeOrMissing(graph: AppGraph, message: String): ModelsStatus {
        val id = graph.modelStore.activeSetId
        return if (id != null && graph.modelStore.state(id) == SetState.COMPLETE) ModelsStatus.Ready(id)
        else ModelsStatus.Missing("$message Скачайте набор на экране «Модели» (появится в следующей версии).")
    }
}
