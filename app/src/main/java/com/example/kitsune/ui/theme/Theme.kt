package com.example.kitsune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = KitsuneOrangeAccent,
    onPrimary = KitsuneBackground,
    primaryContainer = KitsuneBurntBrown,
    onPrimaryContainer = KitsuneTextPrimary,
    secondary = KitsuneTextSecondary,
    onSecondary = KitsuneBackground,
    secondaryContainer = KitsuneSurfaceVariant,
    onSecondaryContainer = KitsuneTextPrimary,
    tertiary = KitsuneOrangeAccentDark,
    onTertiary = KitsuneBackground,
    background = KitsuneBackground,
    onBackground = KitsuneTextPrimary,
    surface = KitsuneSurface,
    onSurface = KitsuneTextPrimary,
    surfaceVariant = KitsuneSurfaceVariant,
    onSurfaceVariant = KitsuneTextSecondary,
    outline = KitsuneOutline,
    error = KitsuneError,
    onError = KitsuneOnError,
    errorContainer = KitsuneBurntBrown,
    onErrorContainer = KitsuneTextPrimary
)

private val LightColorScheme = DarkColorScheme

@Composable
fun KitsuneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}