package ru.neurosteno.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.data.ThemeChoice

/** Токены Revolut DNA. Названия Brutal* сохранены как API совместимости с существующими экранами. */
data class BrutalColors(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val onInk: Color,
    val muted: Color,
    val panel: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val outline: Color,
    val outlineSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val hero: Color,
    val heroEnd: Color,
    val heroMuted: Color,
    val cardDark: Color,
    val onCardDark: Color,
    val dark: Boolean,
)

val LocalBrutal = staticCompositionLocalOf<BrutalColors> { error("NeuroTheme не задана") }

object BrutalDims {
    val Border: Dp = 1.dp
    val BorderThick: Dp = 2.dp
    val Shadow: Dp = 0.dp
    val ShadowSmall: Dp = 0.dp
    val Corner: Dp = 20.dp
    val ControlCorner: Dp = 16.dp
    val SheetCorner: Dp = 28.dp
    val MinTouch: Dp = 48.dp
}

val BrutalShape = RoundedCornerShape(BrutalDims.Corner)
val ControlShape = RoundedCornerShape(BrutalDims.ControlCorner)
val PillShape = RoundedCornerShape(percent = 50)
val SheetShape = RoundedCornerShape(topStart = BrutalDims.SheetCorner, topEnd = BrutalDims.SheetCorner)

val Brutal: BrutalColors @Composable get() = LocalBrutal.current

@Composable
fun NeuroTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val accentVariant = AccentConfig.current
    val accent = if (dark) accentVariant.darkColor else accentVariant.color
    val colors = if (dark) {
        BrutalColors(
            bg = BrutalPalette.PaperDark,
            surface = BrutalPalette.SurfaceDark,
            ink = BrutalPalette.InkDark,
            onInk = BrutalPalette.OnInkDark,
            muted = BrutalPalette.MutedDark,
            panel = BrutalPalette.PanelDark,
            accent = accent,
            accentSoft = BrutalPalette.AccentSoftDark,
            onAccent = BrutalPalette.OnAccentDark,
            outline = BrutalPalette.OutlineDark,
            outlineSoft = BrutalPalette.OutlineSoftDark,
            error = BrutalPalette.ErrorDark,
            errorSoft = BrutalPalette.ErrorSoftDark,
            hero = BrutalPalette.HeroDark,
            heroEnd = BrutalPalette.HeroEndDark,
            heroMuted = BrutalPalette.HeroMuted,
            cardDark = BrutalPalette.CardDarkDark,
            onCardDark = BrutalPalette.OnCardDark,
            dark = true,
        )
    } else {
        BrutalColors(
            bg = BrutalPalette.PaperLight,
            surface = BrutalPalette.SurfaceLight,
            ink = BrutalPalette.Black,
            onInk = BrutalPalette.OnInkLight,
            muted = BrutalPalette.MutedLight,
            panel = BrutalPalette.PanelLight,
            accent = accent,
            accentSoft = BrutalPalette.AccentSoftLight,
            onAccent = BrutalPalette.OnAccentLight,
            outline = BrutalPalette.OutlineLight,
            outlineSoft = BrutalPalette.OutlineSoftLight,
            error = BrutalPalette.Error,
            errorSoft = BrutalPalette.ErrorSoftLight,
            hero = BrutalPalette.HeroLight,
            heroEnd = BrutalPalette.HeroEndLight,
            heroMuted = BrutalPalette.HeroMuted,
            cardDark = BrutalPalette.CardDark,
            onCardDark = BrutalPalette.OnCardDark,
            dark = false,
        )
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft, onPrimaryContainer = colors.ink,
            secondary = colors.ink, onSecondary = colors.onInk,
            secondaryContainer = colors.panel, onSecondaryContainer = colors.ink,
            tertiary = colors.accentSoft, onTertiary = colors.ink,
            tertiaryContainer = colors.panel, onTertiaryContainer = colors.ink,
            background = colors.bg, onBackground = colors.ink,
            surface = colors.surface, onSurface = colors.ink,
            surfaceVariant = colors.panel, onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.surface, surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface, surfaceContainerHigh = colors.panel,
            surfaceContainerHighest = colors.panel,
            outline = colors.outline, outlineVariant = colors.outlineSoft,
            error = colors.error, onError = colors.onAccent,
            errorContainer = colors.errorSoft, onErrorContainer = colors.error,
            scrim = BrutalPalette.Scrim,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent,
            primaryContainer = colors.accentSoft, onPrimaryContainer = colors.ink,
            secondary = colors.ink, onSecondary = colors.onInk,
            secondaryContainer = colors.panel, onSecondaryContainer = colors.ink,
            tertiary = colors.accentSoft, onTertiary = colors.ink,
            tertiaryContainer = colors.panel, onTertiaryContainer = colors.ink,
            background = colors.bg, onBackground = colors.ink,
            surface = colors.surface, onSurface = colors.ink,
            surfaceVariant = colors.panel, onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.surface, surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surface, surfaceContainerHigh = colors.panel,
            surfaceContainerHighest = colors.panel,
            outline = colors.outline, outlineVariant = colors.outlineSoft,
            error = colors.error, onError = colors.onAccent,
            errorContainer = colors.errorSoft, onErrorContainer = colors.error,
            scrim = BrutalPalette.Scrim,
        )
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = ControlShape,
        large = BrutalShape,
        extraLarge = SheetShape,
    )
    CompositionLocalProvider(LocalBrutal provides colors, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = BrutalTypography, shapes = shapes, content = content)
    }
}

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun speakerColor(speakerId: String): Color = speakerFill(speakerId)
