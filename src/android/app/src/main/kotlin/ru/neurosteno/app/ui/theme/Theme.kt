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

/** Цвета системы дизайна (нужные примитивам и экранам; всё остальное -- через [MaterialTheme.colorScheme]). */
data class BrutalColors(
    val bg: Color,
    /** Основной цвет: текст, обводки, тени (чёрный в светлой теме, белый в тёмной). */
    val ink: Color,
    /** Цвет текста НА инк-заливке (инверсия ink). */
    val onInk: Color,
    val muted: Color,
    val panel: Color,
    val accent: Color,
    /** Текст на акценте и на заливках говорящих: всегда чёрный. */
    val onAccent: Color,
    val error: Color,
    val dark: Boolean,
)

val LocalBrutal = staticCompositionLocalOf<BrutalColors> { error("BrutalTheme не задана") }

/** Размеры системы: толщина обводок и смещение жёстких теней. */
object BrutalDims {
    val Border: Dp = 2.5.dp
    val BorderThick: Dp = 3.dp
    val Shadow: Dp = 4.dp
    val ShadowSmall: Dp = 2.dp
    val Corner: Dp = 3.dp
    val MinTouch: Dp = 48.dp
}

val BrutalShape = RoundedCornerShape(BrutalDims.Corner)

val Brutal: BrutalColors @Composable get() = LocalBrutal.current

/** Тема приложения: нео-брутализм, белый/чёрный, один неоновый акцент ([AccentConfig]). [choice] -- выбор пользователя (система/светлая/тёмная). */
@Composable
fun NeuroTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val accent = AccentConfig.current.color
    val colors = if (dark) {
        BrutalColors(BrutalPalette.Black, BrutalPalette.White, BrutalPalette.Black, BrutalPalette.MutedDark, BrutalPalette.PanelDark, accent, BrutalPalette.Black, BrutalPalette.Error, true)
    } else {
        BrutalColors(BrutalPalette.White, BrutalPalette.Black, BrutalPalette.White, BrutalPalette.MutedLight, BrutalPalette.PanelLight, accent, BrutalPalette.Black, BrutalPalette.Error, false)
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = BrutalPalette.Black, primaryContainer = accent, onPrimaryContainer = BrutalPalette.Black,
            secondary = colors.ink, onSecondary = colors.onInk, secondaryContainer = colors.panel, onSecondaryContainer = colors.ink,
            tertiary = accent, onTertiary = BrutalPalette.Black, tertiaryContainer = accent, onTertiaryContainer = BrutalPalette.Black,
            background = colors.bg, onBackground = colors.ink, surface = colors.bg, onSurface = colors.ink,
            surfaceVariant = colors.panel, onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.bg, surfaceContainerLow = colors.bg, surfaceContainer = colors.panel,
            surfaceContainerHigh = colors.panel, surfaceContainerHighest = colors.panel,
            outline = colors.ink, outlineVariant = colors.muted, error = colors.error, onError = BrutalPalette.Black,
            errorContainer = colors.error, onErrorContainer = BrutalPalette.Black, scrim = BrutalPalette.Black,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = BrutalPalette.Black, primaryContainer = accent, onPrimaryContainer = BrutalPalette.Black,
            secondary = colors.ink, onSecondary = colors.onInk, secondaryContainer = colors.panel, onSecondaryContainer = colors.ink,
            tertiary = accent, onTertiary = BrutalPalette.Black, tertiaryContainer = accent, onTertiaryContainer = BrutalPalette.Black,
            background = colors.bg, onBackground = colors.ink, surface = colors.bg, onSurface = colors.ink,
            surfaceVariant = colors.panel, onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.bg, surfaceContainerLow = colors.bg, surfaceContainer = colors.panel,
            surfaceContainerHigh = colors.panel, surfaceContainerHighest = colors.panel,
            outline = colors.ink, outlineVariant = colors.muted, error = colors.error, onError = BrutalPalette.Black,
            errorContainer = colors.error, onErrorContainer = BrutalPalette.Black, scrim = BrutalPalette.Black,
        )
    }
    val shapes = Shapes(
        extraSmall = BrutalShape, small = BrutalShape, medium = BrutalShape, large = BrutalShape, extraLarge = BrutalShape,
    )
    CompositionLocalProvider(LocalBrutal provides colors, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = BrutalTypography, shapes = shapes, content = content)
    }
}

val LocalDarkTheme = staticCompositionLocalOf { false }

/** Цвет говорящего (одинаков во всех экранах и темах). Оставлено для совместимости: см. [speakerFill]. */
@Composable
fun speakerColor(speakerId: String): Color = speakerFill(speakerId)
