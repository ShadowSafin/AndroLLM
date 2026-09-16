package io.androllm.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.R

/**
 * App typography — the sign-in voice, everywhere.
 *
 * Tight-tracked semibold headings (-0.025em, the auth-card hand), 14sp body
 * and medium labels with near-zero tracking. Labels are sentence-case and
 * quiet; no tracked-caps shouting. Display sizes stay bold for hero moments.
 *
 * The family stays the bundled Public Sans variable font
 * (`res/font/public_sans.xml`) so every weight resolves offline.
 */
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
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.4).sp
    ),
    titleSmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
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
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = DeskSans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp
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
