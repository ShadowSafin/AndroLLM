package io.androllm.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark-first color scheme for AndroLLM.
 */
private val DarkColors = darkColorScheme(
    primary = brandPrimary,
    onPrimary = brandOnPrimary,
    primaryContainer = brandSurfaceVariant,
    onPrimaryContainer = brandOnSurface,
    secondary = brandAccent,
    onSecondary = brandOnSurface,
    secondaryContainer = brandSurface,
    onSecondaryContainer = brandOnSurface,
    tertiary = brandPrimary,
    onTertiary = brandOnPrimary,
    tertiaryContainer = brandSurfaceVariant,
    onTertiaryContainer = brandOnSurface,
    error = brandError,
    onError = brandOnError,
    errorContainer = brandErrorContainer,
    onErrorContainer = brandOnErrorContainer,
    background = brandBackground,
    onBackground = brandOnSurface,
    surface = brandSurface,
    onSurface = brandOnSurface,
    surfaceVariant = brandSurfaceVariant,
    onSurfaceVariant = brandOnSurfaceVariant,
    outline = brandOutline,
    outlineVariant = brandOutlineVariant
)

/**
 * Light color scheme for AndroLLM.
 */
private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightOnSurface,
    secondary = brandPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = brandAccent,
    onTertiary = LightOnPrimary,
    tertiaryContainer = LightSurfaceVariant,
    onTertiaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightSurfaceVariant
)

/**
 * The AndroLLM theme. Dark-first by default.
 */
@Composable
fun AndroLLMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AndroLLMTypography,
        shapes = AndroLLMShapes,
        content = content
    )
}
