package ru.neurosteno.app.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** На фиолетовом hero значки статус-бара всегда светлые; при выходе восстанавливаем тему обычного экрана. */
@Composable
fun HeroSystemBars() {
    val view = LocalView.current
    val dark = LocalDarkTheme.current
    DisposableEffect(view, dark) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose {
            controller?.isAppearanceLightStatusBars = !dark
            controller?.isAppearanceLightNavigationBars = !dark
        }
    }
}
