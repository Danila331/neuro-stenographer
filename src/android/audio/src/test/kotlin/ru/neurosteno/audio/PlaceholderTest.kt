package ru.neurosteno.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Юнит-тесты логики декодера живут в :core (Resampler, WavDecoder, Pcm). Здесь -- проверка, что модуль собирается. */
class PlaceholderTest {
    @Test
    fun targetRateIsSixteenKhz() {
        assertEquals(16_000, ru.neurosteno.core.audio.Pcm.TARGET_RATE)
    }
}
