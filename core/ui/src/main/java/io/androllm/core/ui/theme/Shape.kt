package io.androllm.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Writer's Night Desk — shapes.
 * Soft walnut panels, gentle paper slips, and the lamp capsule reserved for
 * the single primary action. Nothing sharp, nothing loud.
 */
val DeskCardShape = RoundedCornerShape(22.dp)
val DeskSlipShape = RoundedCornerShape(16.dp)
val DeskPillShape = RoundedCornerShape(50.dp)
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
    large = RoundedCornerShape(24.dp),
    extraLarge = DeskCardShape
)