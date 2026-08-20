package io.androllm.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Parchment Ledger palette, resolvable per theme.
 *
 * [LightLedger] is the daylight desk; [DarkLedger] is the same desk at night —
 * warm near-black surfaces, warm off-white ink, and the terracotta stamp kept
 * as the single accent so the world survives the switch intact. Components read
 * the current palette via [MaterialTheme.ledger] instead of hardcoding a light
 * token, which is what makes the app's custom surfaces respond to dark mode.
 */
@Immutable
data class LedgerColors(
    val deskNight: Color,
    val deskNightRaised: Color,
    val deskWalnut: Color,
    val deskWalnutRaised: Color,
    val deskWalnutDeep: Color,
    val deskPaper: Color,
    val deskPaperDim: Color,
    val deskInk: Color,
    val deskInkFaint: Color,
    val deskHairline: Color,
    val deskHairlineSoft: Color,
    val lampAmber: Color,
    val lampGlow: Color,
    val lampDeep: Color,
    val lampHalo: Color,
    val inkOnLamp: Color,
    val emberRed: Color,
    val emberRedSoft: Color,
    val emberRedHard: Color,
    val emberOnRed: Color,
    val terracottaSoft: Color,
    val terracottaDeep: Color,
    val cloudWhite: Color,
    val cloudGlassSurface: Color,
    val cloudGlassSurfaceVariant: Color,
    val cloudGlassBorder: Color,
    val cloudGlassBorderHighlight: Color,
    val cloudMoonGlow: Color,
    val cloudParticleTint: Color,
    val revolutCyberCyan: Color,
    val revolutGoldTier: Color,
    val revolutNeonEmerald: Color,
    val sunsetGlowAmber: Color
)

/** The daylight desk — the parchment ledger under the sun. */
val LightLedger = LedgerColors(
    deskNight = Color(0xFFF5F4ED),
    deskNightRaised = Color(0xFFECEBE3),
    deskWalnut = Color(0xFFFBFAF4),
    deskWalnutRaised = Color(0xFFFFFFFF),
    deskWalnutDeep = Color(0xFFEFEEE6),
    deskPaper = Color(0xFF141413),
    deskPaperDim = Color(0xFF4A4945),
    deskInk = Color(0xFF5E5D59),
    deskInkFaint = Color(0xFF8F8D87),
    deskHairline = Color(0xFFE8E6DC),
    deskHairlineSoft = Color(0xFFF0EEE6),
    lampAmber = Color(0xFFD97757),
    lampGlow = Color(0xFFE69D81),
    lampDeep = Color(0xFFB3573E),
    lampHalo = Color(0x40D97757),
    inkOnLamp = Color(0xFFFFFFFF),
    emberRed = Color(0xFFC7442F),
    emberRedSoft = Color(0xFFFBE9E6),
    emberRedHard = Color(0xFF7E1F14),
    emberOnRed = Color(0xFFFFFFFF),
    terracottaSoft = Color(0xFFFFEBE0),
    terracottaDeep = Color(0xFF66251A),
    cloudWhite = Color(0xFFFDFCF8),
    cloudGlassSurface = Color(0xCCFBFAF4),
    cloudGlassSurfaceVariant = Color(0xE6FFFFFF),
    cloudGlassBorder = Color(0x59E8E6DC),
    cloudGlassBorderHighlight = Color(0x99E69D81),
    cloudMoonGlow = Color(0x40E69D81),
    cloudParticleTint = Color(0x59D97757),
    revolutCyberCyan = Color(0xFFE69D81),
    revolutGoldTier = Color(0xFFC08A2E),
    revolutNeonEmerald = Color(0xFF52C41A),
    sunsetGlowAmber = Color(0xFFE69D81)
)

/** The same desk at night — warm near-black, warm ink, terracotta accent. */
val DarkLedger = LedgerColors(
    deskNight = Color(0xFF141312),
    deskNightRaised = Color(0xFF1D1B19),
    deskWalnut = Color(0xFF24211D),
    deskWalnutRaised = Color(0xFF2B2824),
    deskWalnutDeep = Color(0xFF1B1916),
    deskPaper = Color(0xFFE8E4DC),
    deskPaperDim = Color(0xFFB9B4AA),
    deskInk = Color(0xFFA39D92),
    deskInkFaint = Color(0xFF8A8478),
    deskHairline = Color(0xFF3B3731),
    deskHairlineSoft = Color(0xFF2C2925),
    lampAmber = Color(0xFFE08A6A),
    lampGlow = Color(0xFFEAA48C),
    lampDeep = Color(0xFFC77052),
    lampHalo = Color(0x66E08A6A),
    inkOnLamp = Color(0xFF1F1E1B),
    emberRed = Color(0xFFE0604A),
    emberRedSoft = Color(0xFF3B211B),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF1F1E1B),
    terracottaSoft = Color(0xFF3B211B),
    terracottaDeep = Color(0xFFFFB4A0),
    cloudWhite = Color(0xFFF3EFE7),
    cloudGlassSurface = Color(0xCC1D1B19),
    cloudGlassSurfaceVariant = Color(0xE624211D),
    cloudGlassBorder = Color(0x593B3731),
    cloudGlassBorderHighlight = Color(0x99E69D81),
    cloudMoonGlow = Color(0x40E69D81),
    cloudParticleTint = Color(0x59D97757),
    revolutCyberCyan = Color(0xFFE69D81),
    revolutGoldTier = Color(0xFFD9A94F),
    revolutNeonEmerald = Color(0xFF5FCF3D),
    sunsetGlowAmber = Color(0xFFEAA48C)
)

/**
 * The AMOLED desk — the night desk pushed to pure black so every OLED pixel
 * rests. Same warm ink and terracotta accent, identical role names, zero
 * luminance on the ground.
 */
val AmoledLedger = LedgerColors(
    deskNight = Color(0xFF000000),
    deskNightRaised = Color(0xFF0A0A0A),
    deskWalnut = Color(0xFF111111),
    deskWalnutRaised = Color(0xFF1A1A1A),
    deskWalnutDeep = Color(0xFF070707),
    deskPaper = Color(0xFFEDEAE2),
    deskPaperDim = Color(0xFFC4BFB4),
    deskInk = Color(0xFFA6A094),
    deskInkFaint = Color(0xFF75706A),
    deskHairline = Color(0xFF1E1E1E),
    deskHairlineSoft = Color(0xFF161616),
    lampAmber = Color(0xFFE08A6A),
    lampGlow = Color(0xFFEAA48C),
    lampDeep = Color(0xFFC77052),
    lampHalo = Color(0x66E08A6A),
    inkOnLamp = Color(0xFF1F1E1B),
    emberRed = Color(0xFFE0604A),
    emberRedSoft = Color(0xFF2A1512),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF1F1E1B),
    terracottaSoft = Color(0xFF2A1512),
    terracottaDeep = Color(0xFFFFB4A0),
    cloudWhite = Color(0xFFF3EFE7),
    cloudGlassSurface = Color(0xCC111111),
    cloudGlassSurfaceVariant = Color(0xE61A1A1A),
    cloudGlassBorder = Color(0x401E1E1E),
    cloudGlassBorderHighlight = Color(0x99E69D81),
    cloudMoonGlow = Color(0x40E69D81),
    cloudParticleTint = Color(0x59D97757),
    revolutCyberCyan = Color(0xFFE69D81),
    revolutGoldTier = Color(0xFFD9A94F),
    revolutNeonEmerald = Color(0xFF5FCF3D),
    sunsetGlowAmber = Color(0xFFEAA48C)
)