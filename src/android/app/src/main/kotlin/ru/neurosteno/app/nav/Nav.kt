package ru.neurosteno.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Экраны приложения. Стек сохраняется при повороте экрана и пересоздании Activity. */
sealed interface Route {
    data object Home : Route
    data class Transcript(val entryId: String) : Route
    data class Protocol(val entryId: String) : Route
    data object Models : Route
    data object Settings : Route
    data object Stats : Route
    data object Record : Route
}

fun Route.encode(): String = when (this) {
    Route.Home -> "home"
    is Route.Transcript -> "transcript:$entryId"
    is Route.Protocol -> "protocol:$entryId"
    Route.Models -> "models"
    Route.Settings -> "settings"
    Route.Stats -> "stats"
    Route.Record -> "record"
}

fun decodeRoute(s: String): Route = when {
    s.startsWith("transcript:") -> Route.Transcript(s.removePrefix("transcript:"))
    s.startsWith("protocol:") -> Route.Protocol(s.removePrefix("protocol:"))
    s == "models" -> Route.Models
    s == "settings" -> Route.Settings
    s == "stats" -> Route.Stats
    s == "record" -> Route.Record
    else -> Route.Home
}

/** Простой стек экранов (навигационная библиотека не нужна: экранов мало, стек линейный). */
class NavState(initial: List<Route> = listOf(Route.Home)) {
    val stack = mutableStateListOf<Route>().apply { addAll(initial.ifEmpty { listOf(Route.Home) }) }
    val current: Route get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun push(route: Route) {
        if (stack.last() != route) stack.add(route)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Заменяет верхний экран (например, запись -> расшифровка, чтобы «Назад» не возвращал на экран записи). */
    fun replaceTop(route: Route) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(route)
    }

    fun popToHome() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberNavState(): NavState = rememberSaveable(
    saver = listSaver(save = { it.stack.map(Route::encode) }, restore = { NavState(it.map(::decodeRoute)) }),
) { NavState() }
