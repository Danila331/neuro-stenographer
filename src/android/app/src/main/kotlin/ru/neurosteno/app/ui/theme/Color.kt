package ru.neurosteno.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Revolut DNA: фиолетовый hero, нейтральные рабочие панели и графитовые карточки. */
object BrutalPalette {
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF19191D)
    val InkDark = Color(0xFFF5F5F7)

    val PaperLight = Color(0xFFF5F5F5)
    val PaperDark = Color(0xFF0D0D10)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF17171B)

    val MutedLight = Color(0xFF71717C)
    val MutedDark = Color(0xFFA6A6B0)
    val PanelLight = Color(0xFFF2F2F5)
    val PanelDark = Color(0xFF242429)

    val AccentSoftLight = Color(0xFFEEE8FF)
    val AccentSoftDark = Color(0xFF31224F)
    val OutlineLight = Color(0xFF858590)
    val OutlineDark = Color(0xFF777782)
    val OutlineSoftLight = Color(0xFFE7E7EB)
    val OutlineSoftDark = Color(0xFF303036)

    val OnAccentLight = Color(0xFFFFFFFF)
    val OnAccentDark = Color(0xFF19191D)
    val OnInkLight = Color(0xFFFFFFFF)
    val OnInkDark = Color(0xFF19191D)

    val HeroLight = Color(0xFF540DFF)
    val HeroDark = Color(0xFF480BD6)
    val HeroEndLight = Color(0xFF4309C9)
    val HeroEndDark = Color(0xFF290675)
    val HeroMuted = Color(0xFFC2B5FF)
    val CardDark = Color(0xFF29292D)
    val CardDarkDark = Color(0xFF242428)
    val OnCardDark = Color(0xFFFFFFFF)

    val Error = Color(0xFFC9344D)
    val ErrorDark = Color(0xFFFF8CA1)
    val ErrorSoftLight = Color(0xFFFCE9ED)
    val ErrorSoftDark = Color(0xFF40212B)
    val Scrim = Color(0xFF09090B)
}

/** Различимые цвета говорящих, согласованные с фиолетовой темой. */
val SpeakerPalette: List<Color> = listOf(
    Color(0xFF8F6BFF),
    Color(0xFF3BB5A5),
    Color(0xFFF0A24B),
    Color(0xFFD172B8),
    Color(0xFF5B9BD5),
    Color(0xFF65B97A),
    Color(0xFFE77B73),
    Color(0xFFB59A7B),
)

/** Цвет говорящего по номеру (`speaker_3` -> третий цвет, дальше по кругу). */
fun speakerFill(speakerId: String): Color {
    val n = (speakerId.removePrefix("speaker_").toIntOrNull() ?: 1).coerceAtLeast(1)
    return SpeakerPalette[(n - 1) % SpeakerPalette.size]
}
