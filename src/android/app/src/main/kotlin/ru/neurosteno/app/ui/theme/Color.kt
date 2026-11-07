package ru.neurosteno.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Paper, graphite and quiet botanical tones. */
object BrutalPalette {
    val White = Color(0xFFFAF9F6)
    val Black = Color(0xFF30372F)
    val InkDark = Color(0xFF1C211E)
    val MutedLight = Color(0xFF6D746B)
    val MutedDark = Color(0xFFADB6AA)
    val PanelLight = Color(0xFFEEEFE9)
    val PanelDark = Color(0xFF303831)
    val Error = Color(0xFFF3E3DF)
}

val SpeakerPalette: List<Color> = listOf(
    Color(0xFFDDE8D8), Color(0xFFDDE6EC), Color(0xFFEDE1D3), Color(0xFFE5DFF0),
    Color(0xFFDAE2ED), Color(0xFFD8E7E0), Color(0xFFEEDDD9), Color(0xFFE9E3D4),
)

fun speakerFill(speakerId: String): Color {
    val n = (speakerId.removePrefix("speaker_").toIntOrNull() ?: 1).coerceAtLeast(1)
    return SpeakerPalette[(n - 1) % SpeakerPalette.size]
}
