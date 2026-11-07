package ru.neurosteno.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinIntervalGateTest {
    @Test
    fun passesFirstThenAtMostTwicePerSecond() {
        var now = 0L
        val gate = MinIntervalGate(500) { now }
        var passed = 0
        // 8 обновлений в секунду (как было до исправления) -> не больше 2 в секунду
        repeat(40) { i -> now = i * 125L; if (gate.tryPass()) passed++ }
        assertEquals(10, passed) // 5 с -> 10 обновлений
    }

    @Test
    fun forceAlwaysPassesAndRestartsInterval() {
        var now = 0L
        val gate = MinIntervalGate(500) { now }
        assertTrue(gate.tryPass())
        now = 100
        assertFalse(gate.tryPass())
        assertTrue(gate.tryPass(force = true))
        now = 550
        assertFalse(gate.tryPass()) // интервал отсчитывается от принудительного пропуска
        now = 600
        assertTrue(gate.tryPass())
    }
}
