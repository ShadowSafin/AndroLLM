package io.androllm.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Signup Blackout palette, resolvable per theme.
 *
 * Every mode wears the sign-in page: pure-black grounds, white ink, quiet
 * gray secondaries, hairline borders, and the single #EDEDED primary.
 * Components read the current palette via [MaterialTheme.ledger] instead of
 * hardcoding a token, which is what flips the whole app at once.
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

/** The blackout desk with the aurora-violet accent — everywhere. */
val LightLedger = LedgerColors(
    deskNight = Color(0xFF000000),
    deskNightRaised = Color(0xFF0A0A0A),
    deskWalnut = Color(0xFF121212),
    deskWalnutRaised = Color(0xFF1A1A1A),
    deskWalnutDeep = Color(0xFF0A0A0A),
    deskPaper = Color(0xFFFFFFFF),
    deskPaperDim = Color(0xFFEDEDED),
    deskInk = Color(0xFF888888),
    deskInkFaint = Color(0xFF666666),
    deskHairline = Color(0xFF222222),
    deskHairlineSoft = Color(0xFF1E1E1E),
    lampAmber = Color(0xFF7A5CFF),
    lampGlow = Color(0xFFCFC2FF),
    lampDeep = Color(0xFF9D85FF),
    lampHalo = Color(0x407A5CFF),
    inkOnLamp = Color(0xFFFFFFFF),
    emberRed = Color(0xFFF0665F),
    emberRedSoft = Color(0xFF2A1512),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF000000),
    terracottaSoft = Color(0xFF1F1F1F),
    terracottaDeep = Color(0xFFFFFFFF),
    cloudWhite = Color(0xFFFFFFFF),
    cloudGlassSurface = Color(0xCC000000),
    cloudGlassSurfaceVariant = Color(0xE6121212),
    cloudGlassBorder = Color(0x59222222),
    cloudGlassBorderHighlight = Color(0x337A5CFF),
    cloudMoonGlow = Color(0x407A5CFF),
    cloudParticleTint = Color(0x407A5CFF),
    revolutCyberCyan = Color(0xFFCFC2FF),
    revolutGoldTier = Color(0xFF9CA3AF),
    revolutNeonEmerald = Color(0xFF4ADE80),
    sunsetGlowAmber = Color(0xFFCFC2FF)
)

/** Night mode — same blackout + aurora violet; the mode switch stays calm. */
val DarkLedger = LedgerColors(
    deskNight = Color(0xFF000000),
    deskNightRaised = Color(0xFF0A0A0A),
    deskWalnut = Color(0xFF121212),
    deskWalnutRaised = Color(0xFF1A1A1A),
    deskWalnutDeep = Color(0xFF0A0A0A),
    deskPaper = Color(0xFFFFFFFF),
    deskPaperDim = Color(0xFFEDEDED),
    deskInk = Color(0xFF888888),
    deskInkFaint = Color(0xFF666666),
    deskHairline = Color(0xFF222222),
    deskHairlineSoft = Color(0xFF1E1E1E),
    lampAmber = Color(0xFF7A5CFF),
    lampGlow = Color(0xFFCFC2FF),
    lampDeep = Color(0xFF9D85FF),
    lampHalo = Color(0x407A5CFF),
    inkOnLamp = Color(0xFFFFFFFF),
    emberRed = Color(0xFFF0665F),
    emberRedSoft = Color(0xFF2A1512),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF000000),
    terracottaSoft = Color(0xFF1F1F1F),
    terracottaDeep = Color(0xFFFFFFFF),
    cloudWhite = Color(0xFFFFFFFF),
    cloudGlassSurface = Color(0xCC000000),
    cloudGlassSurfaceVariant = Color(0xE6121212),
    cloudGlassBorder = Color(0x59222222),
    cloudGlassBorderHighlight = Color(0x337A5CFF),
    cloudMoonGlow = Color(0x407A5CFF),
    cloudParticleTint = Color(0x407A5CFF),
    revolutCyberCyan = Color(0xFFCFC2FF),
    revolutGoldTier = Color(0xFF9CA3AF),
    revolutNeonEmerald = Color(0xFF4ADE80),
    sunsetGlowAmber = Color(0xFFCFC2FF)
)

/**
 * The AMOLED desk — pure-black floor, aurora-violet accent. Identical role
 * names, zero luminance on the ground.
 */
val AmoledLedger = LedgerColors(
    deskNight = Color(0xFF000000),
    deskNightRaised = Color(0xFF0A0A0A),
    deskWalnut = Color(0xFF121212),
    deskWalnutRaised = Color(0xFF1A1A1A),
    deskWalnutDeep = Color(0xFF070707),
    deskPaper = Color(0xFFFFFFFF),
    deskPaperDim = Color(0xFFEDEDED),
    deskInk = Color(0xFF888888),
    deskInkFaint = Color(0xFF666666),
    deskHairline = Color(0xFF222222),
    deskHairlineSoft = Color(0xFF1E1E1E),
    lampAmber = Color(0xFF7A5CFF),
    lampGlow = Color(0xFFCFC2FF),
    lampDeep = Color(0xFF9D85FF),
    lampHalo = Color(0x407A5CFF),
    inkOnLamp = Color(0xFFFFFFFF),
    emberRed = Color(0xFFF0665F),
    emberRedSoft = Color(0xFF2A1512),
    emberRedHard = Color(0xFFFFB4A0),
    emberOnRed = Color(0xFF000000),
    terracottaSoft = Color(0xFF1F1F1F),
    terracottaDeep = Color(0xFFFFFFFF),
    cloudWhite = Color(0xFFFFFFFF),
    cloudGlassSurface = Color(0xCC000000),
    cloudGlassSurfaceVariant = Color(0xE6121212),
    cloudGlassBorder = Color(0x59222222),
    cloudGlassBorderHighlight = Color(0x337A5CFF),
    cloudMoonGlow = Color(0x407A5CFF),
    cloudParticleTint = Color(0x407A5CFF),
    revolutCyberCyan = Color(0xFFCFC2FF),
    revolutGoldTier = Color(0xFF9CA3AF),
    revolutNeonEmerald = Color(0xFF4ADE80),
    sunsetGlowAmber = Color(0xFFCFC2FF)
)
