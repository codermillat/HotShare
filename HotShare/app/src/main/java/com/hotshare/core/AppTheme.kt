package com.hotshare.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00352C),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF00243A),
    tertiary = Color(0xFFFCD34D),
    background = Color(0xFF0A1020),
    onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF121A2B),
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Color(0xFFAFBACD),
    error = Color(0xFFFCA5A5),
    errorContainer = Color(0xFF4C1D1D)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E9F6E),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0284C7),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFF5F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7EEF7),
    onSurfaceVariant = Color(0xFF475569),
    errorContainer = Color(0xFFFFE4E6)
)

@Composable
fun HotShareTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
