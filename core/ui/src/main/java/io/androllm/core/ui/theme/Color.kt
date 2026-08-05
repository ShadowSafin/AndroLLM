package io.androllm.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Writer's Night Desk — design tokens.
 * A private desk at midnight: your model is the lamp, every conversation a
 * letter you keep. Deep warm night with a single lamp-amber accent; walnut,
 * paper, hairline rules, and ink instead of glass, neon, and gradient chrome.
 */
// ── The night desk ────────────────────────────────────────────────────────────
val DeskNight = Color(0xFF0A0806)          // the room around the desk
val DeskNightRaised = Color(0xFF0E0C08)    // chair / raised ground
val DeskWalnut = Color(0xFF1A150F)         // desk wood, cards
val DeskWalnutRaised = Color(0xFF211B13)   // wood under the lamp
val DeskWalnutDeep = Color(0xFF151008)     // lower desk
val DeskPaper = Color(0xFFEDD9B6)          // warm paper / primary text
val DeskPaperDim = Color(0xFFC8B692)       // well-worn ink
val DeskInk = Color(0xFF8A7A62)            // secondary ink
val DeskInkFaint = Color(0xFF5C5141)       // marginalia
val DeskHairline = Color(0xFF2A2318)       // the ruled line
val DeskHairlineSoft = Color(0xFF201A12)

// ── The lamp (the single accent) ──────────────────────────────────────────────
val LampAmber = Color(0xFFE8A33D)          // the one accent — everything loud is this
val LampGlow = Color(0xFFFFC978)           // lit filament / press feedback
val LampHalo = Color(0x66E8A33D)           // glow ring around the lit dot
val LampDeep = Color(0xFFB06F1E)           // lamp base, pressed state
val InkOnLamp = Color(0xFF180F04)           // text on the amber

// ── Warm error ─────────────────────────────────────────────────────────────────
val EmberRed = Color(0xFFD97762)
val EmberRedSoft = Color(0xFF4A2A22)
val EmberRedHard = Color(0xFF81261D)
val EmberOnRed = Color(0xFF2A0E08)

// ── Legacy aliases mapped into the desk palette ─────────────────────────────────
// Kept so untouched call-sites land inside the same world while screens are
// hand-rebuilt onto the tokens above.
val DeepMidnightBlue = DeskNight
val DarkAtmosphere = DeskNightRaised
val TwilightNavy = DeskWalnut
val CloudShadowIndigo = DeskWalnutDeep

val SkyBlue = LampAmber
val AzureBlue = LampDeep
val CloudWhite = DeskPaper
val MoonSilver = DeskPaperDim
val SoftCyan = LampGlow.copy(alpha = 0.9f)

val SunsetCloudPeach = LampAmber
val SunsetCloudOrange = LampDeep
val SunsetCloudDeepOrange = Color(0xFF8C5420)
val SunsetGlowAmber = LampGlow
val CrescentMoonGold = LampGlow

val RevolutNeonEmerald = LampAmber
val RevolutPlatinum = DeskPaperDim
val RevolutUltraViolet = Color(0xFFB08D6E)
val RevolutGoldTier = LampAmber
val RevolutRoseGold = Color(0xFFE0A489)
val RevolutCyberCyan = LampGlow
val RevolutTitanium = DeskInkFaint
val RevolutDarkCardBackground = DeskWalnut

val LavenderGlow = Color(0xFFC0A288)
val DeepIndigo = Color(0xFF3A2E22)
val PurpleGlow = Color(0xFFA88D72)
val ElectricBlue = LampAmber
val AuroraCyan = LampGlow
val MoonlightWhite = DeskPaper

// Glass aliases now read as the lamp-lit translucent walnut
val CloudGlassSurface = Color(0x2E1A150F)
val CloudGlassSurfaceVariant = Color(0x401F1810)
val CloudGlassBorder = Color(0x33E8A33C)
val CloudGlassBorderHighlight = Color(0x66FFC978)
val CloudMoonGlow = Color(0x40FFC978)
val CloudParticleTint = Color(0x59FFC978)

// Material role mappings
val brandPrimary = LampAmber
val brandAccent = LampAmber
val brandOnPrimary = InkOnLamp

val brandBackground = DeskNight
val brandSurface = DeskNightRaised
val brandSurfaceVariant = DeskWalnut
val brandOnSurface = DeskPaper
val brandOnSurfaceVariant = DeskInk
val brandOutline = DeskHairline
val brandOutlineVariant = DeskHairlineSoft

val brandError = EmberRed
val brandOnError = EmberRedHard
val brandErrorContainer = EmberRedSoft
val brandOnErrorContainer = Color(0xFFF6D2C8)

val onSurface = brandOnSurface

// ── Daylight desk (light theme, retained as the lesser scene) ────────────────────
val LightPrimary = LampDeep
val LightOnPrimary = InkOnLamp
val LightBackground = Color(0xFFF3EAD9)
val LightSurface = Color(0xFFFFF8EC)
val LightOnSurface = Color(0xFF211B13)
val LightSurfaceVariant = Color(0xFFE8DCC4)
val LightOnSurfaceVariant = Color(0xFF6B5A42)
val LightOutline = Color(0xFFC2B090)