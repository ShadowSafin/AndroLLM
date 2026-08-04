package io.androllm.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.CrescentMoonGold
import io.androllm.core.ui.theme.DeepMidnightBlue
import io.androllm.core.ui.theme.SunsetCloudDeepOrange
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach
import io.androllm.core.ui.theme.SunsetGlowAmber

/**
 * Cloud Bugdroid & Crescent Moon Logo Mark.
 * Faithfully matches the official AndroLLM sunset cloud logo.
 */
@Composable
fun CloudBugdroidLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    showMoon: Boolean = true
) {
    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height

            // 1. Crescent Moon in Top Right
            if (showMoon) {
                val moonCenter = Offset(w * 0.82f, h * 0.16f)
                val moonRadius = w * 0.12f

                // Outer Moon Glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CrescentMoonGold.copy(alpha = 0.4f),
                            SunsetGlowAmber.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = moonCenter,
                        radius = moonRadius * 2.2f
                    ),
                    center = moonCenter,
                    radius = moonRadius * 2.2f
                )

                // Crescent Moon Shape (Crescent cutout)
                val moonPath = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(moonCenter, moonRadius))
                }
                val cutoutPath = Path().apply {
                    val cutoutCenter = Offset(moonCenter.x - moonRadius * 0.4f, moonCenter.y - moonRadius * 0.3f)
                    addOval(androidx.compose.ui.geometry.Rect(cutoutCenter, moonRadius * 0.9f))
                }

                drawPath(
                    path = Path.combine(
                        PathOperation.Difference,
                        moonPath,
                        cutoutPath
                    ),
                    brush = Brush.verticalGradient(
                        colors = listOf(CrescentMoonGold, SunsetGlowAmber)
                    )
                )
            }

            // 2. Cloud Bugdroid Sunset Gradient Brush
            val cloudGradient = Brush.verticalGradient(
                colors = listOf(
                    SunsetCloudPeach,
                    SunsetCloudOrange,
                    SunsetCloudDeepOrange
                ),
                startY = h * 0.25f,
                endY = h * 0.85f
            )

            val glowGradient = Brush.radialGradient(
                colors = listOf(
                    SunsetCloudPeach.copy(alpha = 0.45f),
                    SunsetGlowAmber.copy(alpha = 0.2f),
                    Color.Transparent
                ),
                center = Offset(w * 0.5f, h * 0.5f),
                radius = w * 0.6f
            )

            // Outer Ambient Cloud Glow
            drawCircle(
                brush = glowGradient,
                center = Offset(w * 0.5f, h * 0.5f),
                radius = w * 0.55f
            )

            // Antennae
            val leftAntennaStart = Offset(w * 0.38f, h * 0.34f)
            val leftAntennaEnd = Offset(w * 0.28f, h * 0.22f)
            val rightAntennaStart = Offset(w * 0.62f, h * 0.34f)
            val rightAntennaEnd = Offset(w * 0.72f, h * 0.22f)

            drawLine(
                brush = cloudGradient,
                start = leftAntennaStart,
                end = leftAntennaEnd,
                strokeWidth = w * 0.07f,
                cap = StrokeCap.Round
            )
            drawLine(
                brush = cloudGradient,
                start = rightAntennaStart,
                end = rightAntennaEnd,
                strokeWidth = w * 0.07f,
                cap = StrokeCap.Round
            )

            // Bugdroid Head (Half dome composed of overlapping cloud spheres)
            drawCircle(cloudGradient, radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.45f))
            drawCircle(cloudGradient, radius = w * 0.18f, center = Offset(w * 0.35f, h * 0.48f))
            drawCircle(cloudGradient, radius = w * 0.18f, center = Offset(w * 0.65f, h * 0.48f))

            // Bugdroid Body (Fluffy Cloud Base)
            drawCircle(cloudGradient, radius = w * 0.26f, center = Offset(w * 0.5f, h * 0.68f))
            drawCircle(cloudGradient, radius = w * 0.20f, center = Offset(w * 0.32f, h * 0.70f))
            drawCircle(cloudGradient, radius = w * 0.20f, center = Offset(w * 0.68f, h * 0.70f))

            // Left & Right Cloud Arms
            drawCircle(cloudGradient, radius = w * 0.13f, center = Offset(w * 0.20f, h * 0.65f))
            drawCircle(cloudGradient, radius = w * 0.13f, center = Offset(w * 0.80f, h * 0.65f))

            // Eyes (Set into the head)
            val leftEye = Offset(w * 0.41f, h * 0.43f)
            val rightEye = Offset(w * 0.59f, h * 0.43f)
            val eyeRadius = w * 0.045f

            drawCircle(color = DeepMidnightBlue, radius = eyeRadius, center = leftEye)
            drawCircle(color = DeepMidnightBlue, radius = eyeRadius, center = rightEye)
        }
    }
}
