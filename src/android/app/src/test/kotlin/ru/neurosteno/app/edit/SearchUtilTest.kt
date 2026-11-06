package ru.neurosteno.app.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchUtilTest {
    @Test
    fun caseAndYoInsensitive() {
        assertEquals(listOf(0..2), SearchUtil.ranges("Ёлка и ёж", "елк"))
        assertEquals(listOf(0..1, 9..10), SearchUtil.ranges("Ёж и ещё ёж", "еж"))
        assertEquals(2, SearchUtil.ranges("Ёж и ещё ёж", "ЕЖ").size)
    }

    @Test
    fun emptyQueryFindsNothing() {
        assertEquals(emptyList<IntRange>(), SearchUtil.ranges("текст", "  "))
        assertEquals(emptyList<Int>(), SearchUtil.matchingIndices(listOf("a", "b"), ""))
    }

    @Test
    fun matchingIndices() {
        assertEquals(listOf(0, 2), SearchUtil.matchingIndices(listOf("Добрый день", "здравствуйте", "добрый вечер"), "добр"))
    }

    @Test
    fun nonOverlappingOccurrences() {
        assertEquals(listOf(0..1, 2..3), SearchUtil.ranges("аааа", "аа"))
    }
}
