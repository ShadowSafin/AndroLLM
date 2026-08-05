package io.androllm.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * THE WRITER'S NIGHT DESK — direction contract (seed key 26c5d6f6, roll candidate 7).
 * THESIS: The private desk at midnight: your model is the lamp, every conversation
 *   a letter you keep. Owns calm private-instrument warmth; refuses neon glass,
 *   gradient chrome, and every fintech accent stack.
 * OWN-WORLD: deep warm night (DeskNight) under one lamp-amber accent; walnut
 *   panels with hairline rules, paper slips for content, serif display voice,
 *   monospace ledger labels, soft offset shadows, spring press.
 * STORY: the visitor understands this device is their own quiet instrument —
 *   the loaded model glows like a lit lamp, downloads arrive as slips drawn
 *   from a stack, chat reads as correspondence kept after midnight.
 * FIRST VIEWPORT: Models — a serif wordmark above the lamp, one lettered index
 *   card (the loaded model) with its lit lamp dot, download slips beneath,
 *   mono-caps nav bar with the amber lamp on the active tab.
 * FORM: replacement world, assigned direction, seed 26c5d6f6.
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the
 * finish review, the verdict, and DESIGN.md.
 */

/**
 * Dark-first color scheme for AndroLLM.
 */
private val DarkColors = darkColorScheme(
    primary = brandPrimary,
    onPrimary = brandOnPrimary,
    primaryContainer = brandSurfaceVariant,
    onPrimaryContainer = brandOnSurface,
    secondary = brandAccent,
    onSecondary = brandOnPrimary,
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
 * Light color scheme for AndroLLM (daylight desk, secondary scene).
 */
private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightOnSurface,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightPrimary,
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
 * The AndroLLM theme. Dark-first: the desk is used at night.
 *
 * [accentColor] optionally overrides the Material primary/secondary/tertiary
 * slots (and their containers) with a user-chosen accent, e.g. the one picked
 * during profile setup. Every other palette slot keeps the desk defaults so
 * the rest of the design language stays intact.
 */
@Composable
fun AndroLLMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accentColor: Color? = null,
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) DarkColors else LightColors
    val colorScheme = if (accentColor != null) {
        base.copy(
            primary = accentColor,
            onPrimary = Color.White,
            secondary = accentColor,
            onSecondary = Color.White,
            tertiary = accentColor,
            onTertiary = Color.White,
            primaryContainer = accentColor.copy(alpha = 0.22f),
            secondaryContainer = accentColor.copy(alpha = 0.22f),
            tertiaryContainer = accentColor.copy(alpha = 0.22f)
        )
    } else {
        base
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AndroLLMTypography,
        shapes = AndroLLMShapes,
        content = content
    )
}
