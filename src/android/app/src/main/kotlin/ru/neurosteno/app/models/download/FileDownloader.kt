package ru.neurosteno.app.models.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Загрузка не удалась окончательно (контрольная сумма/размер не совпали) -- повторять бессмысленно. */
class DownloadIntegrityException(message: String) : IOException(message)

/** Пользователь отменил загрузку. */
class DownloadCancelledException : IOException("Загрузка отменена")

/**
 * Скачивание одного файла по HTTPS с докачкой: данные пишутся в `<target>.part`, при повторе запрашивается `Range`.
 * Единственное место приложения с сетевым кодом (ArchitectureGuardTest) -- пакет `models.download`.
 */
class FileDownloader(
    private val connect: (String) -> HttpURLConnection = { url -> URL(url).openConnection() as HttpURLConnection },
    private val attempts: Int = 4,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val userAgent: String = "Neurosteno-ModelManager/1.0",
) {
    /**
     * @param expectedSize точный размер файла (из каталога); итоговый файл обязан ему равняться
     * @param onBytes сколько байт этого файла уже на диске (включая докачанные раньше)
     */
    fun download(url: String, target: File, expectedSize: Long, isCancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        var lastError: IOException? = null
        for (attempt in 1..attempts) {
            if (isCancelled()) throw DownloadCancelledException()
            try {
                attemptOnce(url, part, expectedSize, isCancelled, onBytes)
                if (part.length() != expectedSize) {
                    part.delete()
                    throw DownloadIntegrityException("Размер ${target.name}: ожидалось $expectedSize, получено ${part.length()}")
                }
                target.delete()
                if (!part.renameTo(target)) throw IOException("Не удалось переименовать ${part.name}")
                onBytes(expectedSize)
                return
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: DownloadIntegrityException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt < attempts) sleep(2_000L * attempt)
            }
        }
        throw IOException("Не удалось скачать ${target.name}: ${lastError?.message ?: "ошибка сети"}", lastError)
    }

    private fun attemptOnce(url: String, part: File, expectedSize: Long, isCancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have > expectedSize) { part.delete(); have = 0 }
        if (have == expectedSize) { onBytes(have); return }
        val conn = connect(url)
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Encoding", "identity") // Range и точный размер -- без сжатия
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_PARTIAL && have > 0 -> Unit // докачка
                code == HttpURLConnection.HTTP_OK -> have = 0 // сервер отдаёт файл целиком
                code == 416 -> { // диапазон не принят -- начинаем заново
                    part.delete()
                    throw IOException("Сервер отклонил докачку (416), повтор с начала")
                }
                else -> throw IOException("HTTP $code")
            }
            FileOutputStream(part, have > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = have
                    onBytes(total)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - lastReport >= 256 * 1024) { onBytes(total); lastReport = total }
                    }
                    onBytes(total)
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
