package ru.neurosteno.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Pastel variants retained for compatibility with debug preview tools. App colours are defined in Theme.kt. */
enum class AccentVariant(val color: Color, val label: String) {
    GREEN(Color(0xFFE5EDDF), "шалфейный"),
    PINK(Color(0xFFEEDFE2), "припылённый розовый"),
}

object AccentConfig {
    /** Default preview accent. */
    val DEFAULT: AccentVariant = AccentVariant.GREEN

    /** Подмена на лету (только debug: скрипты скриншотов, `DebugStyleReceiver`); в release всегда null. */
    var override: AccentVariant? by mutableStateOf(null)

    val current: AccentVariant get() = override ?: DEFAULT
}
