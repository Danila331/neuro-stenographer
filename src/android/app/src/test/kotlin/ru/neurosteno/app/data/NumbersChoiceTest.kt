package ru.neurosteno.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NumbersChoiceTest {
    @Test
    fun parsesIdsAndDefaultsToConfig() {
        assertEquals(NumbersChoice.WORDS, NumbersChoice.of("words"))
        assertEquals(NumbersChoice.DIGITS, NumbersChoice.of("digits"))
        assertEquals(NumbersChoice.CONFIG, NumbersChoice.of(null))
        assertEquals(NumbersChoice.CONFIG, NumbersChoice.of("что-то"))
    }
}
