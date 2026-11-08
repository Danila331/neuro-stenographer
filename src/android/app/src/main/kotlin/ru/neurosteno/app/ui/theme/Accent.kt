package ru.neurosteno.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Фирменный фиолетовый. Debug-подмена сохранена для проверки контраста альтернативной темы. */
enum class AccentVariant(val color: Color, val darkColor: Color, val label: String) {
    GREEN(Color(0xFF540DFF), Color(0xFF966FFF), "фиолетовый"),
    PINK(Color(0xFF7A3FF2), Color(0xFFB49AFF), "сиреневый"),
}

object AccentConfig {
    val DEFAULT: AccentVariant = AccentVariant.GREEN

    /** Подмена на лету только в debug; в release всегда null. */
    var override: AccentVariant? by mutableStateOf(null)

    val current: AccentVariant get() = override ?: DEFAULT
}
