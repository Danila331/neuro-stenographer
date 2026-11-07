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
    val Corner: Dp = 18.dp
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
    val colors = if (dark) {
        BrutalColors(
            bg = Color(0xFF1C211E), ink = Color(0xFFE8EDE5), onInk = Color(0xFF20271F),
            muted = Color(0xFFADB6AA), panel = Color(0xFF303831), accent = Color(0xFF35483A),
            onAccent = Color(0xFFDCEAD8), error = Color(0xFF49332F), dark = true,
            surface = Color(0xFF252C26), outline = Color(0xFF3B443C), strong = Color(0xFFA8BEA1),
            onStrong = Color(0xFF202E21), errorText = Color(0xFFE7BDB3),
        )
    } else {
        BrutalColors(
            bg = Color(0xFFFAF9F6), ink = Color(0xFF30372F), onInk = Color(0xFFFAFCF8),
            muted = Color(0xFF6D746B), panel = Color(0xFFEEEFE9), accent = Color(0xFFE5EDDF),
            onAccent = Color(0xFF3F573B), error = Color(0xFFF3E3DF), dark = false,
            surface = Color(0xFFFFFFFF), outline = Color(0xFFE3E7DE), strong = Color(0xFF526B4C),
            onStrong = Color(0xFFFFFFFF), errorText = Color(0xFF874F43),
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
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = BrutalShape, large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
    )
    CompositionLocalProvider(LocalBrutal provides colors, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = BrutalTypography, shapes = shapes, content = content)
    }
}

@Composable
fun speakerColor(speakerId: String): Color = speakerFill(speakerId)
