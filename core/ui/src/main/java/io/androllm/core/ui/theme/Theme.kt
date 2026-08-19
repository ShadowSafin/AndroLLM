package io.androllm.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Dark color scheme for AndroLLM — the parchment at night (parchment.dark tokens).
 */
private val DarkColors = darkColorScheme(
    primary = DarkLedger.lampAmber,
    onPrimary = DarkLedger.inkOnLamp,
    primaryContainer = DarkLedger.terracottaSoft,
    onPrimaryContainer = DarkLedger.terracottaDeep,
    secondary = DarkLedger.lampGlow,
    onSecondary = DarkLedger.inkOnLamp,
    secondaryContainer = DarkLedger.terracottaSoft,
    onSecondaryContainer = DarkLedger.terracottaDeep,
    tertiary = DarkLedger.lampAmber,
    onTertiary = DarkLedger.inkOnLamp,
    tertiaryContainer = DarkLedger.terracottaSoft,
    onTertiaryContainer = DarkLedger.terracottaDeep,
    error = DarkLedger.emberRed,
    onError = DarkLedger.emberOnRed,
    errorContainer = DarkLedger.emberRedSoft,
    onErrorContainer = DarkLedger.emberRedHard,
    background = DarkLedger.deskNight,
    onBackground = DarkLedger.deskPaper,
    surface = DarkLedger.deskWalnut,
    onSurface = DarkLedger.deskPaper,
    surfaceVariant = DarkLedger.deskWalnutDeep,
    onSurfaceVariant = DarkLedger.deskInk,
    surfaceTint = DarkLedger.lampAmber,
    inverseSurface = Color(0xFFE8E4DC),
    inverseOnSurface = Color(0xFF141312),
    inversePrimary = DarkLedger.lampDeep,
    surfaceBright = DarkLedger.deskNightRaised,
    surfaceDim = DarkLedger.deskNight,
    surfaceContainerLowest = Color(0xFF10100F),
    surfaceContainerLow = DarkLedger.deskWalnutDeep,
    surfaceContainer = DarkLedger.deskWalnut,
    surfaceContainerHigh = DarkLedger.deskWalnutRaised,
    surfaceContainerHighest = DarkLedger.deskNightRaised,
    outline = DarkLedger.deskHairline,
    outlineVariant = DarkLedger.deskHairlineSoft
)

/**
 * Light color scheme for AndroLLM — the parchment ledger in daylight (flagship).
 */
private val LightColors = lightColorScheme(
    primary = LightLedger.lampAmber,
    onPrimary = LightLedger.inkOnLamp,
    primaryContainer = LightLedger.terracottaSoft,
    onPrimaryContainer = LightLedger.terracottaDeep,
    secondary = LightLedger.lampDeep,
    onSecondary = LightLedger.inkOnLamp,
    secondaryContainer = LightLedger.terracottaSoft,
    onSecondaryContainer = LightLedger.terracottaDeep,
    tertiary = LightLedger.lampAmber,
    onTertiary = LightLedger.inkOnLamp,
    tertiaryContainer = LightLedger.terracottaSoft,
    onTertiaryContainer = LightLedger.terracottaDeep,
    error = LightLedger.emberRed,
    onError = LightLedger.emberOnRed,
    errorContainer = LightLedger.emberRedSoft,
    onErrorContainer = LightLedger.emberRedHard,
    background = LightLedger.deskNight,
    onBackground = LightLedger.deskPaper,
    surface = LightLedger.deskWalnut,
    onSurface = LightLedger.deskPaper,
    surfaceVariant = LightLedger.deskWalnutDeep,
    onSurfaceVariant = LightLedger.deskInk,
    surfaceTint = LightLedger.lampAmber,
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
    outline = LightLedger.deskHairline,
    outlineVariant = LightLedger.deskHairlineSoft
)

/** The active [LedgerColors] for the current composition (light or dark). */
val LocalLedgerColors = staticCompositionLocalOf { LightLedger }

/** Reads the active parchment palette — components should use this, never a hardcoded token. */
val MaterialTheme.ledger: LedgerColors
    @ReadOnlyComposable
    @Composable
    get() = LocalLedgerColors.current

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
    CompositionLocalProvider(LocalLedgerColors provides (if (darkTheme) DarkLedger else LightLedger)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AndroLLMTypography,
            shapes = AndroLLMShapes,
            content = content
        )
    }
}
