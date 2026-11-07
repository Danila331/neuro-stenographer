package ru.neurosteno.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Неоновый акцент темы (B52, docs/team/DESIGN_BRIEF.md): заливки, выделения, активные состояния; текст на акценте -- всегда чёрный.
 * Контраст чёрного текста на акценте (WCAG): [GREEN] 17.3:1, [PINK] 6.0:1; акцент как цвет ТЕКСТА на светлом фоне не используется.
 */
enum class AccentVariant(val color: Color, val label: String) {
    GREEN(Color(0xFFB6FF00), "кислотно-зелёный"),
    PINK(Color(0xFFFF2E88), "неоново-розовый"),
}

object AccentConfig {
    /** Единственная константа выбора акцента: смените на [AccentVariant.PINK], чтобы получить розовую тему. */
    val DEFAULT: AccentVariant = AccentVariant.GREEN

    /** Подмена на лету (только debug: скрипты скриншотов, `DebugStyleReceiver`); в release всегда null. */
    var override: AccentVariant? by mutableStateOf(null)

    val current: AccentVariant get() = override ?: DEFAULT
}
