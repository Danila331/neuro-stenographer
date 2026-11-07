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

/** Component contracts are retained; the processing pipeline is independent of this theme. */
data class BrutalColors(
    val bg: Color, val ink: Color, val onInk: Color, val muted: Color, val panel: Color,
    val accent: Color, val onAccent: Color, val error: Color, val dark: Boolean,
    val surface: Color, val outline: Color, val strong: Color, val onStrong: Color, val errorText: Color,
)

val LocalBrutal = staticCompositionLocalOf<BrutalColors> { error("NeuroTheme is missing") }
val LocalDarkTheme = staticCompositionLocalOf { false }

object BrutalDims {
    val Border: Dp = 1.dp
    val BorderThick: Dp = 1.dp
    val Shadow: Dp = 0.dp
    val ShadowSmall: Dp = 0.dp
    val Corner: Dp = 10.dp
    val MinTouch: Dp = 48.dp
}

val BrutalShape = RoundedCornerShape(BrutalDims.Corner)
val Brutal: BrutalColors @Composable get() = LocalBrutal.current

@Composable
fun NeuroTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    // Notion interface tokens. Blue is reserved for primary actions and focus;
    // selected rows use the matching blue callout background instead of a saturated fill.
    val colors = if (dark) {
        BrutalColors(
            bg = Color(0xFF191919), ink = Color(0xFFD4D4D4), onInk = Color(0xFF191919),
            muted = Color(0xFF9B9B9B), panel = Color(0xFF252525), accent = Color(0xFF1F282D),
            onAccent = Color(0xFFD4D4D4), error = Color(0xFF332523), dark = true,
            surface = Color(0xFF202020), outline = Color(0xFF373737), strong = Color(0xFF2383E2),
            onStrong = Color(0xFFFFFFFF), errorText = Color(0xFFBE524B),
        )
    } else {
        BrutalColors(
            bg = Color(0xFFFFFFFF), ink = Color(0xFF373530), onInk = Color(0xFFFFFFFF),
            muted = Color(0xFF787774), panel = Color(0xFFF1F1EF), accent = Color(0xFFE9F3F7),
            onAccent = Color(0xFF373530), error = Color(0xFFFAECEC), dark = false,
            surface = Color(0xFFFFFFFF), outline = Color(0xFFE9E9E7), strong = Color(0xFF2383E2),
            onStrong = Color(0xFFFFFFFF), errorText = Color(0xFFC4554D),
        )
    }
    val scheme = if (dark) darkColorScheme(
        primary = colors.strong, onPrimary = colors.onStrong,
        primaryContainer = colors.accent, onPrimaryContainer = colors.onAccent,
        secondary = colors.strong, onSecondary = colors.onStrong,
        secondaryContainer = colors.panel, onSecondaryContainer = colors.ink,
        tertiary = colors.strong, onTertiary = colors.onStrong,
        tertiaryContainer = colors.accent, onTertiaryContainer = colors.onAccent,
        background = colors.bg, onBackground = colors.ink, surface = colors.surface, onSurface = colors.ink,
        surfaceVariant = colors.panel, onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.bg, surfaceContainerLow = colors.surface, surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.panel, surfaceContainerHighest = colors.panel,
        outline = colors.outline, outlineVariant = colors.outline,
        error = colors.errorText, onError = colors.bg, errorContainer = colors.error, onErrorContainer = colors.errorText,
    ) else lightColorScheme(
        primary = colors.strong, onPrimary = colors.onStrong,
        primaryContainer = colors.accent, onPrimaryContainer = colors.onAccent,
        secondary = colors.strong, onSecondary = colors.onStrong,
        secondaryContainer = colors.panel, onSecondaryContainer = colors.ink,
        tertiary = colors.strong, onTertiary = colors.onStrong,
        tertiaryContainer = colors.accent, onTertiaryContainer = colors.onAccent,
        background = colors.bg, onBackground = colors.ink, surface = colors.surface, onSurface = colors.ink,
        surfaceVariant = colors.panel, onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.bg, surfaceContainerLow = colors.surface, surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.panel, surfaceContainerHighest = colors.panel,
        outline = colors.outline, outlineVariant = colors.outline,
        error = colors.errorText, onError = colors.bg, errorContainer = colors.error, onErrorContainer = colors.errorText,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
        medium = BrutalShape, large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp),
    )
    CompositionLocalProvider(LocalBrutal provides colors, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = BrutalTypography, shapes = shapes, content = content)
    }
}

@Composable
fun speakerColor(speakerId: String): Color = speakerFill(speakerId, Brutal.dark)
