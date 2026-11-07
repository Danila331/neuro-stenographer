package ru.neurosteno.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentSelectionTest {
    @Test
    fun tappingOutsideExtendsRangeKeepingItContiguous() {
        val s = FragmentSelection(3, 3)
        assertEquals(FragmentSelection(3, 5), s.toggle(5))
        assertEquals(FragmentSelection(1, 3), s.toggle(1))
        assertEquals(3, FragmentSelection(3, 3).toggle(5)!!.size) // размер считается по диапазону, а не по числу нажатий
    }

    @Test
    fun tappingEdgesShrinkAndInnerTrimsTail() {
        val s = FragmentSelection(2, 6)
        assertEquals(FragmentSelection(3, 6), s.toggle(2))
        assertEquals(FragmentSelection(2, 5), s.toggle(6))
        assertEquals(FragmentSelection(2, 4), s.toggle(4))
    }

    @Test
    fun deselectingLastItemEndsSelection() {
        assertNull(FragmentSelection(4, 4).toggle(4))
    }

    @Test
    fun containsAndSize() {
        val s = FragmentSelection(2, 4)
        assertEquals(3, s.size)
        assertTrue(3 in s)
        assertTrue(5 !in s)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (s in listOf(FragmentSelection(0, 0), FragmentSelection(7, 1200), FragmentSelection(Int.MAX_VALUE - 1, Int.MAX_VALUE))) {
            assertEquals(s, FragmentSelection.decode(s.encode()))
        }
    }

    @Test
    fun clampAfterListShrinks() {
        assertEquals(FragmentSelection(1, 2), FragmentSelection.clamp(FragmentSelection(1, 5), 3))
        assertEquals(FragmentSelection(2, 2), FragmentSelection.clamp(FragmentSelection(4, 6), 3))
        assertNull(FragmentSelection.clamp(FragmentSelection(0, 1), 0))
        assertNull(FragmentSelection.clamp(null, 5))
    }
}
