package ru.neurosteno.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка палитры, контраста и геометрии актуальной фиолетовой темы. */
class RevolutThemeTest {
    @Test
    fun paletteMatchesMeasuredRevolutReference() {
        assertEquals(Color(0xFFFFFFFF), BrutalPalette.White)
        assertEquals(Color(0xFF19191D), BrutalPalette.Black)
        assertEquals(Color(0xFF540DFF), BrutalPalette.HeroLight)
        assertEquals(Color(0xFFF2F2F5), BrutalPalette.PanelLight)
        assertEquals(Color(0xFF29292D), BrutalPalette.CardDark)
    }

    @Test
    fun primaryAccentIsRevolutPurpleAndReadable() {
        assertEquals(Color(0xFF540DFF), AccentVariant.GREEN.color)
        assertTrue(contrast(AccentVariant.GREEN.color, BrutalPalette.OnAccentLight) >= 4.5)
    }

    @Test
    fun bodyAndMutedTextMeetAccessibleContrast() {
        assertTrue(contrast(BrutalPalette.Black, BrutalPalette.White) >= 12.0)
        assertTrue(contrast(BrutalPalette.MutedLight, BrutalPalette.White) >= 4.5)
    }

    @Test
    fun geometryUsesRevolutCornersAndAccessibleTargets() {
        assertEquals(1.dp, BrutalDims.Border)
        assertEquals(0.dp, BrutalDims.Shadow)
        assertEquals(20.dp, BrutalDims.Corner)
        assertEquals(16.dp, BrutalDims.ControlCorner)
        assertEquals(28.dp, BrutalDims.SheetCorner)
        assertEquals(48.dp, BrutalDims.MinTouch)
    }

    @Test
    fun screenHeadingsUseInterfaceWeights() {
        assertEquals(FontWeight.SemiBold, BrutalTypography.headlineMedium.fontWeight)
        assertEquals(FontWeight.SemiBold, BrutalTypography.titleLarge.fontWeight)
    }

    @Test
    fun darkTextAndAccentMeetAccessibleContrast() {
        assertTrue(contrast(BrutalPalette.InkDark, BrutalPalette.PaperDark) >= 12.0)
        assertTrue(contrast(BrutalPalette.MutedDark, BrutalPalette.SurfaceDark) >= 4.5)
        assertTrue(contrast(AccentVariant.GREEN.darkColor, BrutalPalette.OnAccentDark) >= 4.5)
    }

    @Test
    fun heroAndDarkCardHaveReadableContent() {
        assertTrue(contrast(BrutalPalette.HeroLight, Color.White) >= 4.5)
        assertTrue(contrast(BrutalPalette.CardDark, BrutalPalette.OnCardDark) >= 12.0)
    }

    @Test
    fun controlOutlinesMeetThreeToOneInBothThemes() {
        assertTrue(contrast(BrutalPalette.OutlineLight, BrutalPalette.SurfaceLight) >= 3.0)
        assertTrue(contrast(BrutalPalette.OutlineDark, BrutalPalette.SurfaceDark) >= 3.0)
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
