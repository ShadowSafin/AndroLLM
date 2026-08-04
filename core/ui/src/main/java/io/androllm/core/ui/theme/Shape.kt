package io.androllm.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Cloud Intelligence Design System — Shapes
 * Floating Cloud Islands (28dp) and Cloud Capsules (50dp Pill)
 */
val CloudIslandShape = RoundedCornerShape(28.dp)
val CloudCapsuleShape = RoundedCornerShape(50.dp)
val CloudMediumShape = RoundedCornerShape(20.dp)
val CloudSmallShape = RoundedCornerShape(12.dp)

val AndroLLMShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = CloudSmallShape,
    medium = CloudMediumShape,
    large = RoundedCornerShape(24.dp),
    extraLarge = CloudIslandShape
)

