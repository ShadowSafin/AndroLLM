package io.androllm.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Parchment Ledger — design tokens.
 * A warm daylight desk: parchment pages, ink text, and one terracotta stamp for
 * every action. Cream rules, editorial serif headlines, and a single warm accent
 * instead of glass, neon, and gradient chrome.
 *
 * Sourced from design-system/ (Claude-inspired): canvas #F5F4ED, ink #141413,
 * terracotta #D97757, muted #5E5D59, cream borders #E8E6DC / #F0EEE6.
 */
// ── The parchment canvas ───────────────────────────────────────────────────────
val DeskNight = Color(0xFFF5F4ED)          // the page — app canvas
val DeskNightRaised = Color(0xFFECEBE3)    // raised ground / layout wash
val DeskWalnut = Color(0xFFFBFAF4)         // warm white card surface
val DeskWalnutRaised = Color(0xFFFFFFFF)   // elevated card / hover surface
val DeskWalnutDeep = Color(0xFFEFEEE6)     // inset well / pressed surface
val DeskPaper = Color(0xFF141413)          // ink — primary text & headings
val DeskPaperDim = Color(0xFF4A4945)       // strong ink (status figures)
val DeskInk = Color(0xFF5E5D59)            // muted ink — secondary text
val DeskInkFaint = Color(0xFF8F8D87)       // faint ink — tertiary / marginalia
val DeskHairline = Color(0xFFE8E6DC)       // the cream rule
val DeskHairlineSoft = Color(0xFFF0EEE6)   // softer cream rule

// ── The terracotta stamp (the single accent) ───────────────────────────────────
val LampAmber = Color(0xFFD97757)          // terracotta — primary actions
val LampGlow = Color(0xFFE69D81)           // terracotta light — hover / glow
val LampHalo = Color(0x40D97757)           // soft terracotta halo
val LampDeep = Color(0xFFB3573E)           // terracotta deep — pressed / focus
val InkOnLamp = Color(0xFFFFFFFF)          // text on terracotta

// ── Warm error ─────────────────────────────────────────────────────────────────
val EmberRed = Color(0xFFC7442F)
val EmberRedSoft = Color(0xFFFBE9E6)
val EmberRedHard = Color(0xFF7E1F14)
val EmberOnRed = Color(0xFFFFFFFF)

// ── Parchment container & dark-variant extras ──────────────────────────────────
val TerracottaSoft = Color(0xFFFFEBE0)     // terracotta wash container
val TerracottaDeep = Color(0xFF66251A)     // terracotta text on light

val DarkCanvas = Color(0xFF141414)
val DarkCanvasRaised = Color(0xFF1D1D1D)
val DarkSurface = Color(0xFF272727)
val DarkText = Color(0xFFDCDCDC)
val DarkTextSecondary = Color(0xFFADADAD)
val DarkTextFaint = Color(0xFF7E7E7E)
val DarkHairline = Color(0xFF3E3E3E)
val DarkHairlineSoft = Color(0xFF303030)
val DarkPrimary = Color(0xFFC78871)
val DarkPrimaryHover = Color(0xFFDCB29F)
val DarkPrimaryDeep = Color(0xFF9D6D5B)
val DarkOnPrimary = Color(0xFF1F1F1C)

// ── Material surface-container roles (parchment daylight) ─────────────────────
val ParchmentLowest = Color(0xFFFFFFFF)
val ParchmentLow = Color(0xFFF7F6EF)
val ParchmentContainer = Color(0xFFF1F0E8)
val ParchmentHigh = Color(0xFFEBEAE1)
val ParchmentHighest = Color(0xFFE4E3D9)
val ParchmentBright = Color(0xFFF5F4ED)
val ParchmentDim = Color(0xFFDDDCD1)
val ParchmentInverse = Color(0xFF2E2D2A)
val OnParchmentInverse = Color(0xFFF2F1E9)

// ── Material surface-container roles (dark variant) ───────────────────────────
val DarkContainerLowest = Color(0xFF0F0F0F)
val DarkContainerLow = Color(0xFF1C1C1C)
val DarkContainer = Color(0xFF232323)
val DarkContainerHigh = Color(0xFF2D2D2D)
val DarkContainerHighest = Color(0xFF383838)
val DarkSurfaceBright = Color(0xFF3D3D3D)
val DarkSurfaceDim = Color(0xFF141414)
val DarkInverse = Color(0xFFDCDCDC)
val OnDarkInverse = Color(0xFF141414)

// ── True AMOLED (OLED) palette — pure black, minimal glow ─────────────────────
// The night desk pushed to its floor: #000000 pages so pixels turn off.
val AmoledNight = Color(0xFF000000)          // canvas — pure black
val AmoledNightRaised = Color(0xFF0A0A0A)    // raised ground / layout wash
val AmoledWalnut = Color(0xFF111111)         // card surface
val AmoledWalnutRaised = Color(0xFF1A1A1A)   // elevated card / hover surface
val AmoledWalnutDeep = Color(0xFF070707)     // inset well / pressed surface
val AmoledPaper = Color(0xFFEDEAE2)          // ink — primary text
val AmoledPaperDim = Color(0xFFC4BFB4)       // strong secondary ink
val AmoledInk = Color(0xFFA6A094)            // muted ink — secondary text
val AmoledInkFaint = Color(0xFF75706A)       // faint ink — tertiary
val AmoledHairline = Color(0xFF1E1E1E)       // rule
val AmoledHairlineSoft = Color(0xFF161616)   // softer rule

// ── AMOLED surface-container roles ───────────────────────────────────────────
val AmoledContainerLowest = Color(0xFF000000)
val AmoledContainerLow = Color(0xFF0F0F0F)
val AmoledContainer = Color(0xFF121212)
val AmoledContainerHigh = Color(0xFF1A1A1A)
val AmoledContainerHighest = Color(0xFF222222)
val AmoledSurfaceBright = Color(0xFF262626)
val AmoledSurfaceDim = Color(0xFF000000)

// ── Legacy aliases mapped into the parchment palette ────────────────────────────
// Kept so untouched call-sites land inside the same world while screens are
// hand-rebuilt onto the tokens above.
val DeepMidnightBlue = DeskNight
val DarkAtmosphere = DeskNightRaised
val TwilightNavy = DeskWalnut
val CloudShadowIndigo = DeskWalnutDeep

val SkyBlue = LampAmber
val AzureBlue = LampDeep
val CloudWhite = Color(0xFFFDFCF8)         // warm white — text on terracotta
val MoonSilver = DeskInkFaint
val SoftCyan = LampGlow.copy(alpha = 0.9f)

val SunsetCloudPeach = LampAmber
val SunsetCloudOrange = LampDeep
val SunsetCloudDeepOrange = Color(0xFF8C3C2A)
val SunsetGlowAmber = LampGlow
val CrescentMoonGold = LampGlow

val RevolutNeonEmerald = Color(0xFF52C41A) // success green (design tokens)
val RevolutPlatinum = Color(0xFFC9C7BE)
val RevolutUltraViolet = Color(0xFFA9886E)
val RevolutGoldTier = Color(0xFFC08A2E)
val RevolutRoseGold = Color(0xFFE0A489)
val RevolutCyberCyan = LampGlow
val RevolutTitanium = DeskInkFaint
val RevolutDarkCardBackground = DeskWalnut

val LavenderGlow = Color(0xFFB08D6E)
val DeepIndigo = Color(0xFF3F3830)
val PurpleGlow = Color(0xFF8C6A4E)
val ElectricBlue = LampAmber
val AuroraCyan = LampGlow
val MoonlightWhite = CloudWhite

// Frosted parchment aliases
val CloudGlassSurface = Color(0xCCFBFAF4)
val CloudGlassSurfaceVariant = Color(0xE6FFFFFF)
val CloudGlassBorder = Color(0x59E8E6DC)
val CloudGlassBorderHighlight = Color(0x99E69D81)
val CloudMoonGlow = Color(0x40E69D81)
val CloudParticleTint = Color(0x59D97757)

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

// ── Light scheme palette (parchment, the flagship) ──────────────────────────────
val LightPrimary = LampAmber
val LightOnPrimary = InkOnLamp
val LightBackground = DeskNight
val LightSurface = DeskWalnut
val LightOnSurface = DeskPaper
val LightSurfaceVariant = DeskWalnutDeep
val LightOnSurfaceVariant = DeskInk
val LightOutline = DeskHairline
