package com.subtitlecreator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = AccentYellow,
    onPrimary = AccentOnDark,
    secondary = AccentYellow,
    background = SurfaceDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceDarkHi,
    onSurfaceVariant = OnSurfaceDim
)

private val LightColors = lightColorScheme(
    primary = AccentOnDark,
    onPrimary = AccentYellow,
    secondary = AccentYellow
)

@Composable
fun SubtitlesCreatorTheme(
    useDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
