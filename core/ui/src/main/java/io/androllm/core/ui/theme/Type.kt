package io.androllm.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.R

/**
 * The Writer's Night Desk typography — Public Sans throughout.
 *
 * One voice for the whole app: the USWDS-style geometric humanist sans that
 * carries the desk's clarity from the biggest display headline to the smallest
 * label. Bundled as a variable font (`res/font/public_sans_variable.ttf`) so
 * every weight resolves without extra files or network access.
 *
 * Scale follows the google-fonts skill's major-third (1.25) @ 16px base rhythm
 * (line-height and letter-spacing tighten as display sizes grow). Labels keep
 * their tracked-caps ledger hand so model meta, tokens, benchmarks and
 * captions read as measured figures.
 */
// res/font/public_sans.xml maps each FontWeight onto the variable font's wght
// axis via android:fontVariationSettings.
private val DeskSans = FontFamily(
    Font(R.font.public_sans, FontWeight.Normal),
    Font(R.font.public_sans, FontWeight.Medium),
    Font(R.font.public_sans, FontWeight.SemiBold),
    Font(R.font.public_sans, FontWeight.Bold)
)

val AndroLLMTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Bold,
        fontSize = 49.sp,
        lineHeight = 54.sp,
        letterSpacing = (-1.2).sp
    ),
    displayMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Bold,
        fontSize = 39.sp,
        lineHeight = 45.sp,
        letterSpacing = (-0.8).sp
    ),
    displaySmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Bold,
        fontSize = 31.sp,
        lineHeight = 39.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.1).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.8.sp
    ),
    labelMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.9.sp
    ),
    labelSmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp
    )
)

/** Ledger figure style for live metrics (tokens, benchmarks, sizes). */
val DeskLedger = TextStyle(
    fontFamily = DeskSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp
)
