package dev.rustbook.pocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RustOrange = Color(0xFFC4532D)
private val RustOrangeLight = Color(0xFFE58A64)

private val LightColors = lightColorScheme(
    primary = RustOrange,
    secondary = Color(0xFF5B5F6B),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = RustOrangeLight,
    secondary = Color(0xFFB8BDC9),
    background = Color(0xFF111317),
    surface = Color(0xFF191C21),
)

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
