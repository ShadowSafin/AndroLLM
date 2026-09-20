package io.androllm.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Signup Blackout — design tokens.
 * The whole app wears the sign-in page: pure-black grounds, white ink,
 * quiet gray secondaries, hairline borders, and a single #EDEDED primary.
 * No terracotta, no warmth, no color except status green/red.
 */
// ── The black canvas ─────────────────────────────────────────────────────────
val DeskNight = Color(0xFF000000)          // the page — app canvas
val DeskNightRaised = Color(0xFF0A0A0A)    // raised ground / layout wash
val DeskWalnut = Color(0xFF121212)         // card surface
val DeskWalnutRaised = Color(0xFF1A1A1A)   // elevated card / hover surface
val DeskWalnutDeep = Color(0xFF0A0A0A)     // inset well / pressed surface
val DeskPaper = Color(0xFFFFFFFF)          // ink — primary text & headings
val DeskPaperDim = Color(0xFFEDEDED)       // strong ink (status figures)
val DeskInk = Color(0xFF888888)            // muted ink — secondary text
val DeskInkFaint = Color(0xFF666666)       // faint ink — tertiary / marginalia
val DeskHairline = Color(0xFF222222)       // the rule
val DeskHairlineSoft = Color(0xFF1E1E1E)   // softer rule

// ── The single accent (aurora violet) ────────────────────────────────────────
val LampAmber = Color(0xFF7A5CFF)          // primary actions — aurora violet
val LampGlow = Color(0xFFCFC2FF)           // highlight / glow — light lavender
val LampHalo = Color(0x407A5CFF)           // soft halo — violet at 25%
val LampDeep = Color(0xFF9D85FF)           // pressed / focus — deep lavender
val InkOnLamp = Color(0xFFFFFFFF)          // text on primary — white on violet

// ── Status red, readable on black ────────────────────────────────────────────
val EmberRed = Color(0xFFF0665F)
val EmberRedSoft = Color(0xFF2A1512)
val EmberRedHard = Color(0xFFFFB4A0)
val EmberOnRed = Color(0xFF000000)

// ── Wash container ───────────────────────────────────────────────────────────
val TerracottaSoft = Color(0xFF1F1F1F)     // wash container
val TerracottaDeep = Color(0xFFFFFFFF)     // text on wash

val DarkCanvas = Color(0xFF000000)
val DarkCanvasRaised = Color(0xFF0A0A0A)
val DarkSurface = Color(0xFF121212)
val DarkText = Color(0xFFFFFFFF)
val DarkTextSecondary = Color(0xFF888888)
val DarkTextFaint = Color(0xFF666666)
val DarkHairline = Color(0xFF222222)
val DarkHairlineSoft = Color(0xFF1E1E1E)
val DarkPrimary = Color(0xFFEDEDED)
val DarkPrimaryHover = Color(0xFFFFFFFF)
val DarkPrimaryDeep = Color(0xFFA3A3A3)
val DarkOnPrimary = Color(0xFF000000)

// ── Material surface-container roles (blackout) ──────────────────────────────
val ParchmentLowest = Color(0xFF000000)
val ParchmentLow = Color(0xFF0A0A0A)
val ParchmentContainer = Color(0xFF121212)
val ParchmentHigh = Color(0xFF1A1A1A)
val ParchmentHighest = Color(0xFF222222)
val ParchmentBright = Color(0xFF1A1A1A)
val ParchmentDim = Color(0xFF000000)
val ParchmentInverse = Color(0xFFFFFFFF)
val OnParchmentInverse = Color(0xFF000000)

// ── Material surface-container roles (dark variant) ──────────────────────────
val DarkContainerLowest = Color(0xFF000000)
val DarkContainerLow = Color(0xFF0A0A0A)
val DarkContainer = Color(0xFF121212)
val DarkContainerHigh = Color(0xFF1A1A1A)
val DarkContainerHighest = Color(0xFF222222)
val DarkSurfaceBright = Color(0xFF1A1A1A)
val DarkSurfaceDim = Color(0xFF000000)
val DarkInverse = Color(0xFFFFFFFF)
val OnDarkInverse = Color(0xFF000000)

// ── True AMOLED palette — pure black, minimal glow ───────────────────────────
val AmoledNight = Color(0xFF000000)          // canvas — pure black
val AmoledNightRaised = Color(0xFF0A0A0A)    // raised ground / layout wash
val AmoledWalnut = Color(0xFF121212)         // card surface
val AmoledWalnutRaised = Color(0xFF1A1A1A)   // elevated card / hover surface
val AmoledWalnutDeep = Color(0xFF070707)     // inset well / pressed surface
val AmoledPaper = Color(0xFFFFFFFF)          // ink — primary text
val AmoledPaperDim = Color(0xFFEDEDED)       // strong secondary ink
val AmoledInk = Color(0xFF888888)            // muted ink — secondary text
val AmoledInkFaint = Color(0xFF666666)       // faint ink — tertiary
val AmoledHairline = Color(0xFF222222)       // rule
val AmoledHairlineSoft = Color(0xFF1E1E1E)   // softer rule

// ── AMOLED surface-container roles ───────────────────────────────────────────
val AmoledContainerLowest = Color(0xFF000000)
val AmoledContainerLow = Color(0xFF0A0A0A)
val AmoledContainer = Color(0xFF121212)
val AmoledContainerHigh = Color(0xFF1A1A1A)
val AmoledContainerHighest = Color(0xFF222222)
val AmoledSurfaceBright = Color(0xFF1A1A1A)
val AmoledSurfaceDim = Color(0xFF000000)

// ── Legacy aliases mapped into the blackout palette ────────────────────────────
val DeepMidnightBlue = DeskNight
val DarkAtmosphere = DeskNightRaised
val TwilightNavy = DeskWalnut
val CloudShadowIndigo = DeskWalnutDeep

val SkyBlue = LampAmber
val AzureBlue = LampDeep
val CloudWhite = Color(0xFFFFFFFF)
val MoonSilver = DeskInkFaint
val SoftCyan = LampGlow.copy(alpha = 0.9f)

val SunsetCloudPeach = LampAmber
val SunsetCloudOrange = LampDeep
val SunsetCloudDeepOrange = Color(0xFF525252)
val SunsetGlowAmber = LampGlow
val CrescentMoonGold = LampGlow

val RevolutNeonEmerald = Color(0xFF4ADE80) // success green, readable on black
val RevolutPlatinum = Color(0xFFC9C7BE)
val RevolutUltraViolet = Color(0xFF6E6E6E)
val RevolutGoldTier = Color(0xFF9CA3AF)
val RevolutRoseGold = Color(0xFF737373)
val RevolutCyberCyan = LampGlow
val RevolutTitanium = DeskInkFaint
val RevolutDarkCardBackground = DeskWalnut

val LavenderGlow = Color(0xFF8A8A8A)
val DeepIndigo = Color(0xFF1A1A1A)
val PurpleGlow = Color(0xFF6E6E6E)
val ElectricBlue = LampAmber
val AuroraCyan = LampGlow
val MoonlightWhite = CloudWhite

// Frosted aliases — black glass
val CloudGlassSurface = Color(0xCC000000)
val CloudGlassSurfaceVariant = Color(0xE6121212)
val CloudGlassBorder = Color(0x59222222)
val CloudGlassBorderHighlight = Color(0x33FFFFFF)
val CloudMoonGlow = Color(0x40FFFFFF)
val CloudParticleTint = Color(0x40FFFFFF)

// Material role mappings
val brandPrimary = LampAmber
val brandAccent = LampAmber
val brandOnPrimary = InkOnLamp

val brandBackground = DeskNight
val brandSurface = DeskWalnut
val brandSurfaceVariant = DeskWalnutDeep
val brandOnSurface = DeskPaper
val brandOnSurfaceVariant = DeskInk
val brandOutline = DeskHairline
val brandOutlineVariant = DeskHairlineSoft

val brandError = EmberRed
val brandOnError = EmberOnRed
val brandErrorContainer = EmberRedSoft
val brandOnErrorContainer = EmberRedHard

val onSurface = brandOnSurface

// ── Light scheme palette (blackout — same black as everywhere) ──────────────────
val LightPrimary = LampAmber
val LightOnPrimary = InkOnLamp
val LightBackground = DeskNight
val LightSurface = DeskWalnut
val LightOnSurface = DeskPaper
val LightSurfaceVariant = DeskWalnutDeep
val LightOnSurfaceVariant = DeskInk
val LightOutline = DeskHairline
