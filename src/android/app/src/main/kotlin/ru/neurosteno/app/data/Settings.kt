package ru.neurosteno.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Язык распознавания: [AUTO] -- определяется по речи (если в конфиге варианта так задано). */
enum class LanguageChoice(val id: String, val label: String) {
    AUTO("auto", "Авто"), RU("ru", "Русский"), EN("en", "English");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * Числа в тексте (`postprocess.numbers` конфига): [DIGITS] -- как выдаёт модель (`as_is`), [WORDS] -- словами («25» -> «двадцать пять»).
 * [CONFIG] -- пользователь ещё не выбирал, действует значение из конфига уровня.
 */
enum class NumbersChoice(val id: String) {
    CONFIG("config"), DIGITS("digits"), WORDS("words");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: CONFIG
    }
}

enum class ThemeChoice(val id: String, val label: String) {
    SYSTEM("system", "Как в системе"), LIGHT("light", "Светлая"), DARK("dark", "Тёмная");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

data class AppSettings(
    val language: LanguageChoice = LanguageChoice.AUTO,
    /** 0 -- автоопределение числа говорящих, 1..8 -- фиксированное. */
    val numSpeakers: Int = 0,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val numbers: NumbersChoice = NumbersChoice.CONFIG,
    /** Пользователь удалил встроенный набор моделей: не восстанавливать его автоматически при запуске. */
    val builtinDeleted: Boolean = false,
) {
    companion object {
        const val MAX_SPEAKERS = 8
    }
}

/** Настройки в SharedPreferences; [state] обновляется сразу, чтобы UI и сервис видели одно и то же. */
class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        language = LanguageChoice.of(prefs.getString(K_LANG, null)),
        numSpeakers = prefs.getInt(K_SPEAKERS, 0).coerceIn(0, AppSettings.MAX_SPEAKERS),
        theme = ThemeChoice.of(prefs.getString(K_THEME, null)),
        numbers = NumbersChoice.of(prefs.getString(K_NUMBERS, null)),
        builtinDeleted = prefs.getBoolean(K_BUILTIN_DELETED, false),
    )

    fun update(change: (AppSettings) -> AppSettings) {
        synchronized(this) {
            val next = change(_state.value)
            prefs.edit()
                .putString(K_LANG, next.language.id)
                .putInt(K_SPEAKERS, next.numSpeakers)
                .putString(K_THEME, next.theme.id)
                .putString(K_NUMBERS, next.numbers.id)
                .putBoolean(K_BUILTIN_DELETED, next.builtinDeleted)
                .apply()
            _state.value = next
        }
    }

    private companion object {
        const val K_LANG = "language"
        const val K_SPEAKERS = "num_speakers"
        const val K_THEME = "theme"
        const val K_NUMBERS = "numbers"
        const val K_BUILTIN_DELETED = "builtin_deleted"
    }
}
