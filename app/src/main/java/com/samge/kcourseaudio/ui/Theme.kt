package com.samge.kcourseaudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF5472F0)
val Accent2 = Color(0xFF7B5CF0)
val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2))

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF101116),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1E26),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF262936),
    onSurfaceVariant = Color(0xFFA7AAB3),
)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF0F0F3),
    onSurfaceVariant = Color(0xFF6E6E73),
)

@Composable
fun KAudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
