package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val StudioDarkColorScheme =
  darkColorScheme(
    primary = StudioCyan,
    secondary = StudioAmber,
    tertiary = StudioEmerald,
    background = StudioBackground,
    surface = StudioSurface,
    surfaceVariant = StudioSurfaceVariant,
    onPrimary = StudioBackground,
    onSecondary = StudioBackground,
    onTertiary = StudioBackground,
    onBackground = StudioOnSurface,
    onSurface = StudioOnSurface,
    onSurfaceVariant = StudioOnSurfaceMuted,
  )

@Composable
fun AutoDuckStudioTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = StudioDarkColorScheme, typography = Typography, content = content)
}
