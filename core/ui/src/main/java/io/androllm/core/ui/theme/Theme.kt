package io.androllm.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * THE PARCHMENT LEDGER — direction contract (seed from design-system/).
 * THESIS: The daylight desk: every conversation is a letter kept in ink on
 *   parchment, every action is a terracotta stamp. Calm editorial warmth that
 *   refuses neon glass, gradient chrome, and every fintech accent stack.
 * OWN-WORLD: warm parchment canvas (#F5F4ED) under one terracotta accent
 *   (#D97757); cream hairline rules, ink text, serif display voice, monospace
 *   ledger labels, soft warm shadows, spring press.
 * STORY: the visitor understands this device is their own quiet instrument —
 *   the loaded model glows like a terracotta seal, downloads arrive as slips
 *   drawn from a stack, chat reads as correspondence kept in daylight.
 * FIRST VIEWPORT: Models — a serif wordmark above the page, one lettered index
 *   card (the loaded model) with its lit terracotta seal, download slips
 *   beneath, mono-caps nav bar with the terracotta stamp on the active tab.
 * FORM: replacement world, assigned direction, seed from design-system files.
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the
 *   finish review, the verdict, and DESIGN.md.
 */

/**
 * Dark color scheme for AndroLLM — the parchment at night (tokens.dark.json).
 */
private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkContainerHigh,
    onPrimaryContainer = DarkText,
    secondary = DarkPrimaryHover,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkContainer,
    onSecondaryContainer = DarkText,
    tertiary = DarkPrimary,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = DarkContainerHigh,
    onTertiaryContainer = DarkText,
    error = Color(0xFFDC6966),
    onError = DarkOnPrimary,
    errorContainer = Color(0xFF3B2322),
    onErrorContainer = Color(0xFFF5D9D6),
    background = DarkCanvas,
    onBackground = DarkText,
    surface = DarkCanvasRaised,
    onSurface = DarkText,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextSecondary,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverse,
    inverseOnSurface = OnDarkInverse,
    inversePrimary = LampDeep,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainerLowest = DarkContainerLowest,
    surfaceContainerLow = DarkContainerLow,
    surfaceContainer = DarkContainer,
    surfaceContainerHigh = DarkContainerHigh,
    surfaceContainerHighest = DarkContainerHighest,
    outline = DarkHairline,
    outlineVariant = DarkHairlineSoft
)

/**
 * Light color scheme for AndroLLM — the parchment ledger in daylight (flagship).
 */
private val LightColors = lightColorScheme(
    primary = brandPrimary,
    onPrimary = brandOnPrimary,
    primaryContainer = TerracottaSoft,
    onPrimaryContainer = TerracottaDeep,
    secondary = LampDeep,
    onSecondary = brandOnPrimary,
    secondaryContainer = TerracottaSoft,
    onSecondaryContainer = TerracottaDeep,
    tertiary = LampAmber,
    onTertiary = brandOnPrimary,
    tertiaryContainer = TerracottaSoft,
    onTertiaryContainer = TerracottaDeep,
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
    surfaceTint = brandPrimary,
    inverseSurface = ParchmentInverse,
    inverseOnSurface = OnParchmentInverse,
    inversePrimary = LampGlow,
    surfaceBright = ParchmentBright,
    surfaceDim = ParchmentDim,
    surfaceContainerLowest = ParchmentLowest,
    surfaceContainerLow = ParchmentLow,
    surfaceContainer = ParchmentContainer,
    surfaceContainerHigh = ParchmentHigh,
    surfaceContainerHighest = ParchmentHighest,
    outline = brandOutline,
    outlineVariant = brandOutlineVariant
)

/**
 * The AndroLLM theme. Light-first: the parchment desk is used in daylight.
 *
 * [accentColor] optionally overrides the Material primary/secondary/tertiary
 * slots (and their containers) with a user-chosen accent, e.g. the one picked
 * during profile setup. Every other palette slot keeps the parchment defaults
 * so the rest of the design language stays intact.
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
            primaryContainer = accentColor.copy(alpha = 0.18f),
            secondaryContainer = accentColor.copy(alpha = 0.18f),
            tertiaryContainer = accentColor.copy(alpha = 0.18f)
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
