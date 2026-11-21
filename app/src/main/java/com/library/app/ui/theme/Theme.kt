package com.library.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Slate800,
    onPrimary = Color.White,
    primaryContainer = Slate100,
    onPrimaryContainer = Slate900,
    secondary = Sand500,
    onSecondary = Color.White,
    secondaryContainer = Sand100,
    onSecondaryContainer = Color(0xFF2C1D0B),
    background = PaperLight,
    onBackground = Color(0xFF171A1F),
    surface = Color.White,
    onSurface = Color(0xFF171A1F),
    surfaceVariant = Slate50,
    onSurfaceVariant = Color(0xFF464B56),
    outline = Color(0xFFC2C7D4),
    outlineVariant = Color(0xFFE2E5ED),
    error = Danger,
    errorContainer = DangerContainer,
)

private val DarkColors = darkColorScheme(
    primary = Slate300,
    onPrimary = Color(0xFF111A2B),
    primaryContainer = Slate700,
    onPrimaryContainer = Slate50,
    secondary = Color(0xFFD9BB93),
    onSecondary = Color(0xFF402C10),
    background = PaperDark,
    onBackground = Color(0xFFE6E8EE),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = SurfaceDarkHigh,
    onSurfaceVariant = Color(0xFFB6BCC8),
    outline = Color(0xFF4A505C),
    outlineVariant = Color(0xFF2A2F38),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
)

@Composable
fun LibraryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LibraryTypography,
        content = content,
    )
}
