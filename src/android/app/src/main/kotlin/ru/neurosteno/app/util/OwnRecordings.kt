package ru.neurosteno.app.util

import java.io.File
import ru.neurosteno.app.data.HistoryEntry

/**
 * Запись с микрофона лежит в `filesDir/recordings`. Исходник удаляется ТОЛЬКО когда копия аудио сохранена в истории
 * (иначе при сбое записи истории пользователь потерял бы оригинал, а расшифровку нельзя было бы пересчитать).
 */
object OwnRecordings {
    /** Можно ли удалять исходник: запись истории создана и в ней есть копия аудио. */
    fun canDelete(entry: HistoryEntry?): Boolean = entry?.audioFile != null

    /** Удаляет [file], если он лежит в каталоге записей [recordingsDir] и копия в истории подтверждена; true -- удалён. */
    fun deleteIfSaved(file: File, recordingsDir: File, entry: HistoryEntry?): Boolean {
        if (!canDelete(entry)) return false
        if (file.absoluteFile.parentFile != recordingsDir.absoluteFile) return false
        return file.delete()
    }
}
