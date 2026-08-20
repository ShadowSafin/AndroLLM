package io.androllm.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Parchment Ledger — shapes.
 * Editorial geometry: generous 24dp cards, soft 12dp wells, and the terracotta
 * capsule reserved for the single primary action. Nothing sharp, nothing loud.
 */
val DeskCardShape = RoundedCornerShape(24.dp)
val DeskSlipShape = RoundedCornerShape(12.dp)
val DeskPillShape = RoundedCornerShape(32.dp)
val DeskSmallShape = RoundedCornerShape(10.dp)

// Legacy aliases
val CloudIslandShape = DeskCardShape
val CloudCapsuleShape = DeskPillShape
val CloudMediumShape = DeskSlipShape
val CloudSmallShape = DeskSmallShape

val AndroLLMShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = DeskSmallShape,
    medium = DeskSlipShape,
    large = RoundedCornerShape(20.dp),
    extraLarge = DeskCardShape
)