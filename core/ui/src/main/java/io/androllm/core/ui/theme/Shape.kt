package io.androllm.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Parchment Ledger — shapes.
 * Editorial geometry: gentle 8px paper slips, quiet cards, and the terracotta
 * capsule reserved for the single primary action. Nothing sharp, nothing loud.
 */
val DeskCardShape = RoundedCornerShape(16.dp)
val DeskSlipShape = RoundedCornerShape(10.dp)
val DeskPillShape = RoundedCornerShape(32.dp)
val DeskSmallShape = RoundedCornerShape(8.dp)

// Legacy aliases
val CloudIslandShape = DeskCardShape
val CloudCapsuleShape = DeskPillShape
val CloudMediumShape = DeskSlipShape
val CloudSmallShape = DeskSmallShape

val AndroLLMShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = DeskSmallShape,
    medium = DeskSlipShape,
    large = RoundedCornerShape(14.dp),
    extraLarge = DeskCardShape
)
