package ru.neurosteno.store

import io.objectbox.Box
import io.objectbox.BoxStore
import java.io.File
import ru.neurosteno.core.search.VectorStore

/**
 * [VectorStore] на ObjectBox (HNSW, косинус). ObjectBox: Kotlin/Java API открыт (Apache-2.0), НАТИВНАЯ библиотека `libobjectbox-jni.so`
 * закрытая (бинарная лицензия ObjectBox GmbH, бесплатна для приложений без Sync). Размерность индекса задана аннотацией [ChunkVec]
 * (384, USER2-small); для другой размерности используйте `BruteForceVectorStore` -- об этом заботится фабрика приложения.
 *
 * Поиск по одной записи -- точный перебор векторов этой записи (десятки-сотни окон: быстрее и надёжнее HNSW, который сначала берёт
 * k ближайших во всей истории, а уже потом фильтрует по условиям). Поиск по всей истории -- HNSW.
 */
class ObjectBoxVectorStore(private val store: BoxStore, private val dim: Int = DIM) : VectorStore {
    private val box: Box<ChunkVec> = store.boxFor(ChunkVec::class.java)

    init {
        require(dim == DIM) { "ObjectBoxVectorStore рассчитан на $DIM-мерные векторы, получено $dim" }
    }

    override fun upsert(recordId: String, chunkIds: List<Long>, vectors: List<FloatArray>) {
        require(chunkIds.size == vectors.size) { "ids и vectors разной длины" }
        vectors.forEach { require(it.size == dim) { "размер вектора ${it.size} != $dim" } }
        store.runInTx {
            remove(recordId)
            box.put(chunkIds.mapIndexed { i, id -> ChunkVec(id, recordId, vectors[i]) })
        }
    }

    private fun remove(recordId: String) {
        box.query(ChunkVec_.recordId.equal(recordId)).build().use { it.remove() }
    }

    override fun deleteRecord(recordId: String) = remove(recordId)

    override fun search(q: FloatArray, k: Int, recordId: String?): List<Pair<Long, Float>> {
        require(q.size == dim) { "размер запроса ${q.size} != $dim" }
        if (k <= 0) return emptyList()
        if (recordId != null) {
            val rows = box.query(ChunkVec_.recordId.equal(recordId)).build().use { it.find() }
            return rows.map { r ->
                var dot = 0f
                val v = r.vec!!
                for (j in 0 until dim) dot += v[j] * q[j]
                r.id to dot
            }.sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first }).take(k)
        }
        return box.query(ChunkVec_.vec.nearestNeighbors(q, k)).build().use { query ->
            query.findWithScores().map { it.get().id to (1f - it.score.toFloat()) }
        }.sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
    }

    override fun recordIds(): Set<String> =
        box.query().build().use { it.property(ChunkVec_.recordId).distinct().findStrings()?.toSet() ?: emptySet() }

    override fun count(): Int = box.count().toInt()

    override fun close() = store.close()

    companion object {
        const val DIM = 384

        /** Открывает базу в каталоге [dir] (на Android -- с контекстом приложения для загрузки нативной библиотеки). */
        fun open(dir: File, androidContext: Any? = null): ObjectBoxVectorStore {
            val builder = MyObjectBox.builder().directory(dir)
            if (androidContext != null) builder.androidContext(androidContext)
            return ObjectBoxVectorStore(builder.build())
        }
    }
}
