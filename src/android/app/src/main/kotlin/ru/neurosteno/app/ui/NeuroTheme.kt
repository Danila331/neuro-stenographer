package ru.neurosteno.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    onPrimary = Color.White,
    secondary = Color(0xFF3E7CB1),
)

@Composable
fun NeuroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
