package ru.neurosteno.core.search

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Окно + его основы (BM25 строится без повторной токенизации). */
data class StoredChunk(val chunk: Chunk, val terms: List<String>)

/**
 * Хранилище окон (текст, время, говорящие, основы) без векторов. Единственный источник правды о том, что проиндексировано;
 * векторы лежат в [VectorStore] под теми же идентификаторами окон.
 */
interface ChunkStore {
    /** Резервирует [count] подряд идущих идентификаторов окон; возвращает первый. Идентификаторы не повторяются между запусками. */
    fun reserveIds(count: Int): Long

    /** Заменяет все окна записи. */
    fun replace(recordId: String, chunks: List<StoredChunk>)

    fun delete(recordId: String)

    fun recordIds(): Set<String>

    fun chunks(recordId: String): List<StoredChunk>

    /** Все окна в стабильном порядке (по записям в порядке добавления, внутри записи -- по времени). */
    fun all(): List<StoredChunk>
}

class MemoryChunkStore : ChunkStore {
    private var next = 1L
    private val byRecord = LinkedHashMap<String, List<StoredChunk>>()

    @Synchronized override fun reserveIds(count: Int): Long = next.also { next += count }

    @Synchronized override fun replace(recordId: String, chunks: List<StoredChunk>) { byRecord[recordId] = chunks }

    @Synchronized override fun delete(recordId: String) { byRecord.remove(recordId) }

    @Synchronized override fun recordIds(): Set<String> = byRecord.keys.toSet()

    @Synchronized override fun chunks(recordId: String): List<StoredChunk> = byRecord[recordId].orEmpty()

    @Synchronized override fun all(): List<StoredChunk> = byRecord.values.flatten()
}

/**
 * Файловое хранилище: `<dir>/<sha1(recordId)>.json` на запись (атомарная замена) и `next_id` -- счётчик идентификаторов. Порядок записей --
 * по времени создания файла (стабилен между запусками благодаря порядковому номеру `seq` в файле).
 */
class FileChunkStore(private val dir: File) : ChunkStore {
    @Serializable
    private class ChunkDto(
        val id: Long, val utts: List<Int>, val speakers: List<String>, val startMs: Long, val endMs: Long, val text: String, val embedText: String,
        val terms: List<String>,
    )

    @Serializable
    private class RecordDto(val recordId: String, val seq: Long, val chunks: List<ChunkDto>)

    private val json = Json { ignoreUnknownKeys = true }
    private val loaded = LinkedHashMap<String, Pair<Long, List<StoredChunk>>>() // recordId -> (seq, окна)
    private var next = 1L
    private var seq = 1L

    init {
        dir.mkdirs()
        File(dir, NEXT_ID).takeIf { it.isFile }?.let { next = it.readText().trim().toLongOrNull() ?: 1L }
        val parsed = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            runCatching { json.decodeFromString(RecordDto.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull().also { if (it == null) f.delete() }
        }.sortedBy { it.seq }
        for (r in parsed) {
            loaded[r.recordId] = r.seq to r.chunks.map { c ->
                StoredChunk(Chunk(c.id, r.recordId, c.utts, c.speakers, c.startMs, c.endMs, c.text, c.embedText), c.terms)
            }
            seq = maxOf(seq, r.seq + 1)
            next = maxOf(next, (r.chunks.maxOfOrNull { it.id } ?: 0L) + 1)
        }
    }

    private fun fileFor(recordId: String) =
        File(dir, MessageDigest.getInstance("SHA-1").digest(recordId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24) + ".json")

    @Synchronized
    override fun reserveIds(count: Int): Long {
        val first = next
        next += count
        File(dir, NEXT_ID).writeText(next.toString())
        return first
    }

    @Synchronized
    override fun replace(recordId: String, chunks: List<StoredChunk>) {
        val s = loaded[recordId]?.first ?: seq++
        val dto = RecordDto(recordId, s, chunks.map { sc ->
            val c = sc.chunk
            ChunkDto(c.id, c.utts, c.speakers, c.startMs, c.endMs, c.text, c.embedText, sc.terms)
        })
        val f = fileFor(recordId)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(RecordDto.serializer(), dto), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "не удалось сохранить ${f.name}" } }
        loaded[recordId] = s to chunks
    }

    @Synchronized
    override fun delete(recordId: String) {
        loaded.remove(recordId)
        fileFor(recordId).delete()
    }

    @Synchronized override fun recordIds(): Set<String> = loaded.keys.toSet()

    @Synchronized override fun chunks(recordId: String): List<StoredChunk> = loaded[recordId]?.second.orEmpty()

    @Synchronized override fun all(): List<StoredChunk> = loaded.values.sortedBy { it.first }.flatMap { it.second }

    private companion object {
        const val NEXT_ID = "next_id"
    }
}
