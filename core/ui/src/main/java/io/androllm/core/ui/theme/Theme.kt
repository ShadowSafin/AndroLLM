package io.androllm.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.androllm.core.models.ChatFontSize
import io.androllm.core.models.ThemeMode
import io.androllm.core.models.UiDensity

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
 * True AMOLED dark scheme — pure #000000 ground so OLED pixels switch off.
 * Keeps the same warm ink and terracotta accent for a consistent identity.
 */
private val AmoledColors = darkColorScheme(
    primary = AmoledLedger.lampAmber,
    onPrimary = AmoledLedger.inkOnLamp,
    primaryContainer = AmoledLedger.terracottaSoft,
    onPrimaryContainer = AmoledLedger.terracottaDeep,
    secondary = AmoledLedger.lampGlow,
    onSecondary = AmoledLedger.inkOnLamp,
    secondaryContainer = AmoledLedger.terracottaSoft,
    onSecondaryContainer = AmoledLedger.terracottaDeep,
    tertiary = AmoledLedger.lampAmber,
    onTertiary = AmoledLedger.inkOnLamp,
    tertiaryContainer = AmoledLedger.terracottaSoft,
    onTertiaryContainer = AmoledLedger.terracottaDeep,
    error = AmoledLedger.emberRed,
    onError = AmoledLedger.emberOnRed,
    errorContainer = AmoledLedger.emberRedSoft,
    onErrorContainer = AmoledLedger.emberRedHard,
    background = Color.Black,
    onBackground = AmoledLedger.deskPaper,
    surface = AmoledLedger.deskWalnut,
    onSurface = AmoledLedger.deskPaper,
    surfaceVariant = AmoledLedger.deskWalnutDeep,
    onSurfaceVariant = AmoledLedger.deskInk,
    surfaceTint = AmoledLedger.lampAmber,
    inverseSurface = Color(0xFFE8E4DC),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = AmoledLedger.lampDeep,
    surfaceBright = AmoledLedger.deskNightRaised,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = AmoledLedger.deskWalnutDeep,
    surfaceContainer = AmoledLedger.deskWalnut,
    surfaceContainerHigh = AmoledLedger.deskWalnutRaised,
    surfaceContainerHighest = AmoledLedger.deskNightRaised,
    outline = AmoledLedger.deskHairline,
    outlineVariant = AmoledLedger.deskHairlineSoft
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
 * The AndroLLM theme. Resolves the [ThemeMode] to the matching palette:
 *  - [ThemeMode.SYSTEM] follows the OS dark/light setting;
 *  - [ThemeMode.AMOLED] is true-black for OLED displays;
 *  - [ThemeMode.DARK] / [ThemeMode.LIGHT] force the parchment night/day desk.
 *
 * [dynamicColor] enables Material You dynamic color on Android 12+; the
 * wallpaper-derived scheme is blended with the ledger identity via
 * [accentColor] when the user has chosen one during profile setup.
 */
@Composable
fun AndroLLMTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    accentColor: Color? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val amoled = themeMode == ThemeMode.AMOLED

    val context = LocalContext.current
    val dynamicScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }

    val base = when {
        dynamicScheme != null -> dynamicScheme
        amoled -> AmoledColors
        darkTheme -> DarkColors
        else -> LightColors
    }

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

    val ledger = when {
        amoled -> AmoledLedger
        darkTheme -> DarkLedger
        else -> LightLedger
    }

    CompositionLocalProvider(LocalLedgerColors provides ledger) {
        CompositionLocalProvider(
            // The desk ink, not raw black: every uncolored Text inherits this
            // instead of the Compose default (pure black), which would vanish
            // on the night/AMOLED desk.
            LocalContentColor provides colorScheme.onBackground
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = AndroLLMTypography,
                shapes = AndroLLMShapes,
                content = content
            )
        }
    }
}

/**
 * Applies the user's density (compact / default / comfortable) and chat font
 * size as a global scale. Overriding [LocalDensity] lets every dp-based
 * spacing and every sp-based text react without touching a single component.
 * The scaling multiplies the platform density/fontScale so system font-size
 * settings continue to apply on top.
 */
@Composable
fun ProvideUiScale(
    density: UiDensity = UiDensity.DEFAULT,
    fontSize: ChatFontSize = ChatFontSize.MEDIUM,
    content: @Composable () -> Unit
) {
    val base = LocalDensity.current
    val densityFactor = when (density) {
        UiDensity.COMPACT -> 0.92f
        UiDensity.DEFAULT -> 1f
        UiDensity.COMFORTABLE -> 1.08f
    }
    val fontFactor = when (fontSize) {
        ChatFontSize.SMALL -> 0.9f
        ChatFontSize.MEDIUM -> 1f
        ChatFontSize.LARGE -> 1.15f
    }
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = base.density * densityFactor,
            fontScale = base.fontScale * fontFactor
        )
    ) {
        content()
    }
}