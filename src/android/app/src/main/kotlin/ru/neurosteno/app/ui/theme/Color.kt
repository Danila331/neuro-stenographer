package ru.neurosteno.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Базовые цвета: чёрный текст и обводки на белом; в тёмной теме -- инверсия. */
object BrutalPalette {
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
    val InkDark = Color(0xFF0B0B0B)
    /** Второстепенный текст: 8.6:1 на белом, 9.6:1 (светлее) на чёрном. */
    val MutedLight = Color(0xFF4A4A4A)
    val MutedDark = Color(0xFFC9C9C9)
    /** Спокойные панели: сетка/подложки. */
    val PanelLight = Color(0xFFF1F1F1)
    val PanelDark = Color(0xFF1A1A1A)
    /** Ошибка: единственный «второй» цвет (чёрный текст на нём -- 6.4:1). */
    val Error = Color(0xFFFF5252)
}

/**
 * Цвета говорящих: восемь различимых неоновых заливок с ЧЁРНЫМ текстом (контраст >= 7:1 у каждой), одинаковые во всех экранах и
 * обеих темах. Среди них нет ни кислотно-зелёного, ни неоново-розового -- чтобы не сливаться с акцентом любой из тем.
 */
val SpeakerPalette: List<Color> = listOf(
    Color(0xFFFFE500), // жёлтый
    Color(0xFF00E5FF), // голубой
    Color(0xFFFF9F1C), // оранжевый
    Color(0xFFB28DFF), // фиолетовый
    Color(0xFF6C9BFF), // синий
    Color(0xFF3DFFB5), // мятный
    Color(0xFFFF6F59), // коралловый
    Color(0xFFFFC9A8), // персиковый
)

/** Цвет говорящего по номеру (`speaker_3` -> третий цвет, дальше по кругу). */
fun speakerFill(speakerId: String): Color {
    val n = (speakerId.removePrefix("speaker_").toIntOrNull() ?: 1).coerceAtLeast(1)
    return SpeakerPalette[(n - 1) % SpeakerPalette.size]
}
