package ru.neurosteno.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEstimatorTest {
    @Test
    fun noDataNoEstimate() {
        assertNull(EtaEstimator.remainingMs(1_000, 0.01f, null, 0.0))
    }

    @Test
    fun priorRtfBeforeProgress() {
        // запись 60 с, RTF 0.5 -> всего 30 с, прошло 5 с
        assertEquals(25_000L, EtaEstimator.remainingMs(5_000, 0.01f, 60_000, 0.5))
    }

    @Test
    fun extrapolationByProgress() {
        // за 10 с сделано 50 % -> ещё 10 с
        assertEquals(10_000L, EtaEstimator.remainingMs(10_000, 0.5f, null, 0.0))
        assertEquals(10_000L, EtaEstimator.remainingMs(10_000, 0.5f, 60_000, 0.9))
    }

    @Test
    fun blendBetweenRtfAndProgress() {
        // по прогрессу: 10 с при 20 % -> ещё 40 с; по RTF: 100 с * 0.5 - 10 с = 40 с; оценки совпали
        assertEquals(40_000.0, EtaEstimator.remainingMs(10_000, 0.2f, 100_000, 0.5)!!.toDouble(), 20.0)
        // оценки разошлись: результат между ними
        val mixed = EtaEstimator.remainingMs(10_000, 0.2f, 100_000, 1.0)!!
        assertTrue(mixed in 40_000L..90_000L)
    }

    @Test
    fun finishedIsZero() {
        assertEquals(0L, EtaEstimator.remainingMs(1_000, 1f, null, 0.0))
    }
}
