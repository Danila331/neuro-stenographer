package ru.neurosteno.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Notion neutrals: white canvas, warm graphite text and quiet gray surfaces. */
object BrutalPalette {
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF373530)
    val InkDark = Color(0xFF191919)
    val MutedLight = Color(0xFF787774)
    val MutedDark = Color(0xFF9B9B9B)
    val PanelLight = Color(0xFFF1F1EF)
    val PanelDark = Color(0xFF252525)
    val Error = Color(0xFFFAECEC)
}

/** Exact Notion callout backgrounds, ordered to keep neighbouring speakers distinct. */
private val SpeakerPaletteLight = listOf(
    Color(0xFFE9F3F7), // blue
    Color(0xFFF1F1EF), // gray
    Color(0xFFEEF3ED), // green
    Color(0xFFFAF3DD), // yellow
    Color(0xFFF6F3F8), // purple
    Color(0xFFF8ECDF), // orange
    Color(0xFFF9F2F5), // pink
    Color(0xFFF3EEEE), // brown
)

private val SpeakerPaletteDark = listOf(
    Color(0xFF1F282D),
    Color(0xFF252525),
    Color(0xFF242B26),
    Color(0xFF372E20),
    Color(0xFF2A2430),
    Color(0xFF36291F),
    Color(0xFF2E2328),
    Color(0xFF2E2724),
)

private val SpeakerInkLight = listOf(
    Color(0xFF487CA5), Color(0xFF787774), Color(0xFF548164), Color(0xFFC29343),
    Color(0xFF8A67AB), Color(0xFFCC782F), Color(0xFFB35488), Color(0xFF976D57),
)

private val SpeakerInkDark = listOf(
    Color(0xFF447ACB), Color(0xFF9B9B9B), Color(0xFF4F9768), Color(0xFFC19138),
    Color(0xFF865DBB), Color(0xFFCB7B37), Color(0xFFBA4A78), Color(0xFFA27763),
)

private fun speakerIndex(speakerId: String): Int {
    val n = (speakerId.removePrefix("speaker_").toIntOrNull() ?: 1).coerceAtLeast(1)
    return (n - 1) % SpeakerPaletteLight.size
}

/** Light values are also used by the PDF exporter; screen components pass their actual theme. */
fun speakerFill(speakerId: String, dark: Boolean = false): Color =
    (if (dark) SpeakerPaletteDark else SpeakerPaletteLight)[speakerIndex(speakerId)]

fun speakerInk(speakerId: String, dark: Boolean = false): Color =
    (if (dark) SpeakerInkDark else SpeakerInkLight)[speakerIndex(speakerId)]
