package ru.neurosteno.core.audio

import java.io.InputStream

/** Чтение потока целиком в ОДИН массив (без цепочки «readBytes -> head + rest»). */
object StreamBytes {
    /**
     * [prefix] (уже прочитанное начало потока) + остаток [s]. Размер остатка берётся из `available()`, если поток его знает;
     * массив растёт только при расхождении. null -- данных больше [maxBytes].
     */
    fun readAll(prefix: ByteArray, s: InputStream, maxBytes: Int): ByteArray? {
        val hint = runCatching { s.available() }.getOrDefault(0).coerceIn(0, maxBytes)
        var buf = ByteArray(prefix.size + hint)
        System.arraycopy(prefix, 0, buf, 0, prefix.size)
        var len = prefix.size
        val limit = prefix.size.toLong() + maxBytes
        while (true) {
            if (len == buf.size) {
                val b = s.read() // массив заполнен ровно: конец потока -- результат без копии
                if (b < 0) return buf
                if (len + 1L > limit) return null
                buf = buf.copyOf(minOf(maxOf(buf.size.toLong() * 2, 8192L), limit).toInt())
                buf[len++] = b.toByte()
                continue
            }
            val r = s.read(buf, len, buf.size - len)
            if (r < 0) break
            len += r
        }
        return if (len == buf.size) buf else buf.copyOf(len)
    }
}
