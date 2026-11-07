package ru.neurosteno.core.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Накопитель PCM 16 кГц для декодера (B58). Раньше: растущий массив (удвоение с копией при каждом росте) + полная копия при `finish` --
 * у 30-минутной записи (109 МБ) одновременно жили 2-3 копии, на устройстве с 4 ГБ это OOM.
 *
 * Теперь: короткая запись (до [spillAfterSamples]) -- блоки по [chunkSamples] в памяти; длиннее -- накопленное уходит во временный файл
 * (сырые float32) и дальше пишется потоком, в памяти остаётся один блок. [toFloatArray] выделяет ОДИН массив точного размера и читает в него
 * блоками: пик ≈ запись + 1-2 блока (при «только память» без файла -- запись + блоки, поэтому для длинных записей нужен [spillDir]).
 * Временный файл удаляется в [toFloatArray] и [close].
 */
class PcmAccumulator(
    private val maxSamples: Long,
    private val chunkSamples: Int = DEFAULT_CHUNK,
    private val spillDir: File? = null,
    private val spillAfterSamples: Long = DEFAULT_SPILL_AFTER,
) : Closeable {
    class TooLongException(message: String) : RuntimeException(message)

    private val chunks = ArrayList<FloatArray>()
    private var cur: FloatArray? = null
    private var curLen = 0
    var size: Long = 0
        private set

    private var file: File? = null
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var writeBuf: ByteBuffer? = null

    /** Пик одновременно живых отсчётов в памяти (блоки + итоговый массив) -- для проверки в тестах. */
    var peakLiveSamples: Long = 0
        private set
    private var live = 0L

    /** true -- данные ушли во временный файл. */
    val spilled: Boolean get() = file != null

    private fun alloc(n: Int): FloatArray {
        live += n
        if (live > peakLiveSamples) peakLiveSamples = live
        return FloatArray(n)
    }

    private fun release(n: Int) { live -= n }

    fun append(x: FloatArray, length: Int = x.size) {
        if (length == 0) return
        if (size + length > maxSamples) throw TooLongException("Запись слишком длинная (больше $maxSamples отсчётов)")
        var off = 0
        while (off < length) {
            var c = cur
            if (c == null || curLen == c.size) {
                c?.let { flushOrKeep(it) }
                c = alloc(chunkSamples)
                cur = c
                curLen = 0
            }
            val n = minOf(length - off, c.size - curLen)
            System.arraycopy(x, off, c, curLen, n)
            curLen += n
            off += n
        }
        size += length
    }

    /** Полный блок: в файл, если запись уже длинная (и есть куда), иначе остаётся в памяти. */
    private fun flushOrKeep(c: FloatArray) {
        if (file == null && spillDir != null && (chunks.size + 1).toLong() * chunkSamples > spillAfterSamples) startSpill()
        if (file != null) {
            writeToFile(c, c.size)
            release(c.size)
        } else {
            chunks += c
        }
    }

    private fun startSpill() {
        try {
            val f = File.createTempFile("pcm-", ".f32", spillDir)
            file = f
            raf = RandomAccessFile(f, "rw").also { channel = it.channel }
            writeBuf = ByteBuffer.allocateDirect(WRITE_BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (kept in chunks) { writeToFile(kept, kept.size); release(kept.size) }
            chunks.clear()
        } catch (e: IOException) {
            // нет места/каталога -- продолжаем в памяти (как раньше), но без падения
            closeFile()
        }
    }

    private fun writeToFile(a: FloatArray, n: Int) {
        val ch = channel ?: return
        val buf = writeBuf!!
        var i = 0
        while (i < n) {
            buf.clear()
            val k = minOf(n - i, buf.capacity() / 4)
            buf.asFloatBuffer().put(a, i, k)
            buf.limit(k * 4)
            while (buf.hasRemaining()) ch.write(buf)
            i += k
        }
    }

    /** Итог точного размера. После вызова накопитель пуст. */
    fun toFloatArray(): FloatArray {
        require(size <= Int.MAX_VALUE) { "слишком много отсчётов" }
        val lastLen = curLen
        val last = cur
        cur = null
        curLen = 0
        try {
            if (file != null) {
                last?.let { writeToFile(it, lastLen); release(it.size) }
                chunks.clear()
                val out = alloc(size.toInt())
                val ch = channel!!
                ch.position(0)
                val buf = ByteBuffer.allocateDirect(WRITE_BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                while (pos < out.size) {
                    buf.clear()
                    buf.limit(minOf(buf.capacity(), (out.size - pos) * 4))
                    var got = 0
                    while (buf.hasRemaining()) {
                        val r = ch.read(buf)
                        if (r < 0) throw IOException("временный файл PCM оборван")
                        got += r
                    }
                    buf.flip()
                    buf.asFloatBuffer().get(out, pos, got / 4)
                    pos += got / 4
                }
                return out
            }
            last?.let { chunks += it }
            val out = alloc(size.toInt())
            var pos = 0
            for (c in chunks) {
                val n = if (c === last) lastLen else c.size
                System.arraycopy(c, 0, out, pos, n)
                pos += n
                release(c.size) // блок можно освободить сразу
            }
            chunks.clear()
            return out
        } finally {
            size = 0
            closeFile()
        }
    }

    private fun closeFile() {
        runCatching { channel?.close() }
        runCatching { raf?.close() }
        file?.let { runCatching { it.delete() } }
        channel = null; raf = null; file = null; writeBuf = null
    }

    /** Освободить ресурсы без результата (отмена/ошибка декодирования). */
    override fun close() {
        chunks.clear()
        cur = null
        closeFile()
    }

    companion object {
        /** 1 Мотсчёт = 4 МБ. */
        const val DEFAULT_CHUNK = 1 shl 20

        /** До 5 минут (4,8 М отсчётов, 19 МБ) держим в памяти, дальше -- временный файл. */
        const val DEFAULT_SPILL_AFTER = 16_000L * 60 * 5
        private const val WRITE_BLOCK_BYTES = 1 shl 18
    }
}
