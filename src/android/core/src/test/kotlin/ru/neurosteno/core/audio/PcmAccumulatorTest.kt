package ru.neurosteno.core.audio

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PcmAccumulatorTest {
    private fun ramp(n: Int, from: Int = 0) = FloatArray(n) { (from + it).toFloat() }

    @Test
    fun contentAndExactSizeSurviveArbitrarySlicing() {
        val acc = PcmAccumulator(1_000_000, chunkSamples = 1000)
        val all = ramp(12_345)
        var pos = 0
        for (step in listOf(1, 7, 999, 1000, 1001, 5000, 4337)) {
            val n = minOf(step, all.size - pos)
            acc.append(all.copyOfRange(pos, pos + n))
            pos += n
        }
        assertEquals(all.size.toLong(), acc.size)
        assertArrayEquals(all, acc.toFloatArray())
        assertEquals(0L, acc.size)
    }

    @Test
    fun appendWithLengthUsesOnlyTheLeadingPart() {
        val acc = PcmAccumulator(100, chunkSamples = 8)
        acc.append(floatArrayOf(1f, 2f, 3f, 9f, 9f), 3)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), acc.toFloatArray())
    }

    @Test
    fun emptyAccumulatorGivesEmptyArray() {
        assertEquals(0, PcmAccumulator(10).toFloatArray().size)
    }

    @Test
    fun exceedingTheLimitFailsWithAClearError() {
        val acc = PcmAccumulator(10, chunkSamples = 4)
        acc.append(ramp(10))
        assertThrows<PcmAccumulator.TooLongException> { acc.append(ramp(1)) }
    }

    @Test
    fun longRecordingSpillsToDiskAndKeepsContentExact() {
        val dir = java.nio.file.Files.createTempDirectory("pcm").toFile()
        try {
            val acc = PcmAccumulator(10_000_000, chunkSamples = 1000, spillDir = dir, spillAfterSamples = 5000)
            val all = ramp(23_456)
            var pos = 0
            while (pos < all.size) {
                val n = minOf(777, all.size - pos)
                acc.append(all.copyOfRange(pos, pos + n))
                pos += n
            }
            assertTrue(acc.spilled)
            assertEquals(1, dir.list()!!.size) // один временный файл
            assertArrayEquals(all, acc.toFloatArray())
            assertEquals(0, dir.list()!!.size) // после результата файла нет
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun closeDeletesTheSpillFile() {
        val dir = java.nio.file.Files.createTempDirectory("pcm").toFile()
        try {
            val acc = PcmAccumulator(10_000_000, chunkSamples = 100, spillDir = dir, spillAfterSamples = 300)
            acc.append(ramp(5000))
            assertTrue(acc.spilled)
            acc.close()
            assertEquals(0, dir.list()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun brokenSpillDirFallsBackToMemory() {
        val missing = java.io.File(java.nio.file.Files.createTempDirectory("pcm").toFile(), "no/such/dir")
        val acc = PcmAccumulator(10_000_000, chunkSamples = 100, spillDir = missing, spillAfterSamples = 300)
        val all = ramp(2000)
        acc.append(all)
        assertArrayEquals(all, acc.toFloatArray())
    }

    @Test
    fun thirtyMinutesPeakIsOneRecordNotTwoOrThree() {
        // 30 минут 16 кГц = 28,8 М отсчётов; поток приходит кусками по 4096 отсчётов, как от декодера
        val total = 16_000L * 60 * 30
        val dir = java.nio.file.Files.createTempDirectory("pcm").toFile()
        try {
            val acc = PcmAccumulator(Long.MAX_VALUE, PcmAccumulator.DEFAULT_CHUNK, dir)
            val piece = FloatArray(4096) { it.toFloat() }
            var fed = 0L
            while (fed < total) {
                val n = minOf(piece.size.toLong(), total - fed).toInt()
                acc.append(piece, n)
                fed += n
            }
            assertTrue(acc.spilled)
            val out = acc.toFloatArray()
            assertEquals(total, out.size.toLong())
            assertEquals(4095f, out[4095]); assertEquals(0f, out[4096]); assertEquals(1023f, out[out.size - 1])
            // пик в памяти: итоговый массив + не более двух блоков (раньше -- 2,07 записи: удвоение массива + copyOf при finish)
            val bound = total + 2L * PcmAccumulator.DEFAULT_CHUNK
            assertTrue(acc.peakLiveSamples <= bound, "пик ${acc.peakLiveSamples} > $bound")
            var cap = 16_000L * 30; var len = 0L; var legacyPeak = 0L; var live = cap
            while (len < total) {
                len += 4096
                if (len > cap) { val newCap = maxOf(cap * 2, len); live += newCap; legacyPeak = maxOf(legacyPeak, live); live -= cap; cap = newCap }
            }
            legacyPeak = maxOf(legacyPeak, live + total) // finish: out.copyOf(outLen) при живом out
            assertTrue(legacyPeak > 2 * total, "прежний пик $legacyPeak должен быть > 2x записи ($total)")
            println("PCM 30 мин: прежний пик ${legacyPeak * 4 / 1048576} МБ, новый пик ${acc.peakLiveSamples * 4 / 1048576} МБ (запись ${total * 4 / 1048576} МБ)")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readAllReturnsExactArrayWithoutCopyWhenStreamKnowsItsSize() {
        val data = ByteArray(100_000) { it.toByte() }
        val r = StreamBytes.readAll(data.copyOfRange(0, 12), ByteArrayInputStream(data, 12, data.size - 12), 1 shl 20)
        assertNotNull(r)
        assertArrayEquals(data, r)
    }

    @Test
    fun readAllWorksForStreamsWithoutAvailableAndRespectsLimit() {
        val data = ByteArray(50_000) { (it * 7).toByte() }
        fun noAvail(d: ByteArray, off: Int) = object : InputStream() {
            var p = off
            override fun read(): Int = if (p < d.size) d[p++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, o: Int, l: Int): Int { if (p >= d.size) return -1; val n = minOf(l, d.size - p, 777); System.arraycopy(d, p, b, o, n); p += n; return n }
            override fun available() = 0
        }
        assertArrayEquals(data, StreamBytes.readAll(data.copyOfRange(0, 12), noAvail(data, 12), 1 shl 20))
        assertNull(StreamBytes.readAll(data.copyOfRange(0, 12), noAvail(data, 12), 10_000))
    }
}
