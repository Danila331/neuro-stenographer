package ru.neurosteno.core.search

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Хранилище векторов окон (косинус; векторы L2-нормированы). Реализации: [BruteForceVectorStore] (`:core`, int8 + переранжирование f32;
 * lite и запасной путь) и `ObjectBoxVectorStore` (`:app`, HNSW).
 */
interface VectorStore : AutoCloseable {
    /** Заменяет ВСЕ векторы записи [recordId] новыми (идентификаторы окон [chunkIds] глобально уникальны). */
    fun upsert(recordId: String, chunkIds: List<Long>, vectors: List<FloatArray>)

    fun deleteRecord(recordId: String)

    /** Ближайшие по косинусу: пары (идентификатор окна, косинус) по убыванию; [recordId] -- только эта запись. */
    fun search(q: FloatArray, k: Int, recordId: String? = null): List<Pair<Long, Float>>

    /** Идентификаторы записей, у которых есть векторы (для сверки с хранилищем окон). */
    fun recordIds(): Set<String>

    /** Сколько векторов всего. */
    fun count(): Int

    override fun close() {}
}

/**
 * Полный перебор: в памяти int8-матрица (round(x * 127)), по ней отбираются кандидаты (4k, не меньше 64), затем точный косинус по f32.
 * f32-векторы лежат в файле записи ([dir]) и читаются позиционно только для кандидатов; при [dir] == null всё в памяти (тесты).
 * Формат файла записи `<sha1(recordId)>.vec`: `u32 dim, u32 n, n * i64 chunkId, n * dim * f32` (little endian); запись атомарна
 * (временный файл + rename), каталог переживает перезапуск приложения (индексы int8 восстанавливаются при открытии).
 */
class BruteForceVectorStore(private val dim: Int, private val dir: File? = null) : VectorStore {
    private class Rec(val recordId: String, val ids: LongArray, val q8: ByteArray, val f32: FloatArray?, val file: File?)

    private val recs = LinkedHashMap<String, Rec>()

    init {
        dir?.mkdirs()
        dir?.listFiles { f -> f.name.endsWith(".vec") }?.forEach { f -> runCatching { load(f) }.onFailure { f.delete() } }
    }

    private fun fileFor(recordId: String): File? = dir?.let { File(it, sha1(recordId) + ".vec") }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun quantize(v: FloatArray, out: ByteArray, off: Int) {
        for (j in 0 until dim) out[off + j] = Math.round(v[j] * 127f).coerceIn(-127, 127).toByte()
    }

    @Synchronized
    override fun upsert(recordId: String, chunkIds: List<Long>, vectors: List<FloatArray>) {
        require(chunkIds.size == vectors.size) { "ids и vectors разной длины" }
        val n = vectors.size
        val q8 = ByteArray(n * dim)
        vectors.forEachIndexed { i, v ->
            require(v.size == dim) { "размер вектора ${v.size} != $dim" }
            quantize(v, q8, i * dim)
        }
        val ids = chunkIds.toLongArray()
        val file = fileFor(recordId)
        var mem: FloatArray? = null
        if (file != null) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            val bb = ByteBuffer.allocate(8 + n * 8 + n * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(dim).putInt(n)
            for (id in ids) bb.putLong(id)
            for (v in vectors) for (x in v) bb.putFloat(x)
            tmp.writeBytes(bb.array())
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "не удалось сохранить ${file.name}" } }
            recordIdFile(file, recordId)
        } else {
            mem = FloatArray(n * dim).also { a -> vectors.forEachIndexed { i, v -> v.copyInto(a, i * dim) } }
        }
        recs[recordId] = Rec(recordId, ids, q8, mem, file)
    }

    /** Имя записи нужно при загрузке: кладём рядом маленький файл `.id` (имя файла -- хэш). */
    private fun recordIdFile(vec: File, recordId: String) = File(vec.parentFile, vec.name + ".id").writeText(recordId, Charsets.UTF_8)

    private fun load(f: File) {
        val id = File(f.parentFile, f.name + ".id").readText(Charsets.UTF_8)
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(8).also { raf.readFully(it) }
            val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val d = hb.getInt()
            val n = hb.getInt()
            require(d == dim && n >= 0) { "размерность файла $d != $dim" }
            val idsBytes = ByteArray(n * 8).also { raf.readFully(it) }
            val ids = LongArray(n).also { ByteBuffer.wrap(idsBytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(it) }
            val q8 = ByteArray(n * dim)
            val row = ByteArray(dim * 4)
            val fb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
            val tmp = FloatArray(dim)
            for (i in 0 until n) {
                raf.readFully(row)
                fb.rewind()
                fb.asFloatBuffer().get(tmp)
                quantize(tmp, q8, i * dim)
            }
            recs[id] = Rec(id, ids, q8, null, f)
        }
    }

    @Synchronized
    override fun deleteRecord(recordId: String) {
        val r = recs.remove(recordId) ?: fileFor(recordId)?.let { Rec(recordId, LongArray(0), ByteArray(0), null, it) }
        r?.file?.let { it.delete(); File(it.parentFile, it.name + ".id").delete(); File(it.parentFile, it.name + ".tmp").delete() }
    }

    @Synchronized
    override fun recordIds(): Set<String> = recs.keys.toSet()

    @Synchronized
    override fun count(): Int = recs.values.sumOf { it.ids.size }

    @Synchronized
    override fun search(q: FloatArray, k: Int, recordId: String?): List<Pair<Long, Float>> {
        require(q.size == dim) { "размер запроса ${q.size} != $dim" }
        if (k <= 0) return emptyList()
        val scope = if (recordId != null) listOfNotNull(recs[recordId]) else recs.values.toList()
        val total = scope.sumOf { it.ids.size }
        if (total == 0) return emptyList()
        val q8 = ByteArray(dim).also { quantize(q, it, 0) }
        val cand = maxOf(4 * k, 64).coerceAtMost(total)
        // отбор кандидатов по int8-скалярному произведению: куча из cand лучших
        class Cand(val score: Int, val ri: Int, val i: Int)
        val heap = java.util.PriorityQueue<Cand>(cand + 1, compareBy<Cand> { it.score }.thenByDescending { it.ri }.thenByDescending { it.i })
        for ((ri, r) in scope.withIndex()) {
            for (i in r.ids.indices) {
                var acc = 0
                val base = i * dim
                for (j in 0 until dim) acc += r.q8[base + j] * q8[j]
                if (heap.size < cand) heap += Cand(acc, ri, i) else if (acc > heap.peek().score) { heap.poll(); heap += Cand(acc, ri, i) }
            }
        }
        val top = heap.toList()
        // переранжирование точными f32
        val out = ArrayList<Pair<Long, Float>>(top.size)
        val buf = FloatArray(dim)
        for (c in top) {
            val r = scope[c.ri]
            val i = c.i
            readVector(r, i, buf)
            var dot = 0f
            for (j in 0 until dim) dot += buf[j] * q[j]
            out += r.ids[i] to dot
        }
        out.sortWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
        return out.take(k)
    }

    private fun readVector(r: Rec, i: Int, out: FloatArray) {
        r.f32?.let { System.arraycopy(it, i * dim, out, 0, dim); return }
        val file = checkNotNull(r.file)
        RandomAccessFile(file, "r").use { raf ->
            val n = r.ids.size
            raf.seek(8L + n * 8L + i.toLong() * dim * 4)
            val row = ByteArray(dim * 4)
            raf.readFully(row)
            ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        }
    }
}
