package ru.neurosteno.store

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

@Entity
data class ChunkVec(
    @Id(assignable = true) var id: Long = 0,
    @Index var recordId: String = "",
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE, neighborsPerNode = 32, indexingSearchCount = 200) var vec: FloatArray? = null,
)
