package io.androllm.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Fill
import io.androllm.core.ui.theme.CloudShadowIndigo
import io.androllm.core.ui.theme.CrescentMoonGold
import io.androllm.core.ui.theme.DarkAtmosphere
import io.androllm.core.ui.theme.DeepMidnightBlue
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach
import io.androllm.core.ui.theme.SunsetGlowAmber
import io.androllm.core.ui.theme.TwilightNavy
import kotlin.random.Random

/**
 * Atmospheric 6-Layer Background System — Sunset Twilight Theme
 * Matches the logo: Twilight starry sky with crescent moon, drifting warm sunset clouds.
 */
@Composable
fun CloudAtmosphericBackground(
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CloudAtmosphereTransition")

    // Animations (Subtle, slow 60fps movement)
    val moonPulse = if (reduceMotion) 1f else infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "moonPulse"
    ).value

    val cloudDrift = if (reduceMotion) 0f else infiniteTransition.animateFloat(
        initialValue = -60f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 26000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cloudDrift"
    ).value

    val particleShift = if (reduceMotion) 0f else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleShift"
    ).value

    val particles = remember {
        List(28) {
            ParticleData(
                xPct = Random.nextFloat(),
                yPct = Random.nextFloat(),
                radius = Random.nextFloat() * 2.2f + 1f,
                alphaSeed = Random.nextFloat()
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DeepMidnightBlue,
                        DarkAtmosphere,
                        TwilightNavy,
                        CloudShadowIndigo
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Layer 2: Golden Crescent Moon in Top-Right Atmosphere (Positioned clear of top-bar icons)
            val moonCenter = Offset(width * 0.74f, height * 0.065f)
            val moonRadius = width * 0.065f * moonPulse

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CrescentMoonGold.copy(alpha = 0.35f),
                        SunsetGlowAmber.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = moonCenter,
                    radius = moonRadius * 2.5f
                ),
                center = moonCenter,
                radius = moonRadius * 2.5f
            )

            val moonPath = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(moonCenter, moonRadius))
            }
            val cutoutPath = Path().apply {
                val cutoutCenter = Offset(moonCenter.x - moonRadius * 0.4f, moonCenter.y - moonRadius * 0.3f)
                addOval(androidx.compose.ui.geometry.Rect(cutoutCenter, moonRadius * 0.9f))
            }
            drawPath(
                path = Path.combine(PathOperation.Difference, moonPath, cutoutPath),
                brush = Brush.verticalGradient(listOf(CrescentMoonGold, SunsetGlowAmber))
            )

            // Layer 3: Warm Sunset Orange Cloud Formations
            val sunsetCloudPath = Path().apply {
                moveTo(-100f + cloudDrift, height * 0.35f)
                cubicTo(
                    width * 0.3f + cloudDrift, height * 0.28f,
                    width * 0.6f - cloudDrift, height * 0.42f,
                    width + 100f, height * 0.32f
                )
                lineTo(width + 100f, height)
                lineTo(-100f, height)
                close()
            }
            drawPath(
                path = sunsetCloudPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SunsetCloudPeach.copy(alpha = 0.12f),
                        SunsetCloudOrange.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    startY = height * 0.3f,
                    endY = height
                ),
                style = Fill
            )

            // Layer 4: Lower Atmospheric Mist & Sunset Reflection
            val mistPath = Path().apply {
                moveTo(-50f, height * 0.65f)
                cubicTo(
                    width * 0.4f - cloudDrift * 0.5f, height * 0.60f,
                    width * 0.7f + cloudDrift * 0.5f, height * 0.72f,
                    width + 50f, height * 0.66f
                )
                lineTo(width + 50f, height)
                lineTo(-50f, height)
                close()
            }
            drawPath(
                path = mistPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        SunsetGlowAmber.copy(alpha = 0.08f),
                        CloudShadowIndigo.copy(alpha = 0.4f)
                    ),
                    startY = height * 0.6f,
                    endY = height
                )
            )

            // Layer 5: Twinkling Stardust Particles
            particles.forEach { p ->
                val floatYPct = (p.yPct - (particleShift / 360f * 0.12f)) % 1f
                val realY = if (floatYPct < 0) (1f + floatYPct) * height else floatYPct * height
                val realX = p.xPct * width
                val twinkleAlpha = (0.25f + 0.55f * kotlin.math.sin(particleShift * 0.05f + p.alphaSeed * 10f)).coerceIn(0.1f, 0.85f)

                drawCircle(
                    color = if (p.xPct > 0.5f) SunsetCloudPeach.copy(alpha = twinkleAlpha * 0.6f) else SkyBlue.copy(alpha = twinkleAlpha * 0.5f),
                    radius = p.radius,
                    center = Offset(realX, realY)
                )
            }

            // Layer 6: Soft Bloom Ambient Lighting
            val bloomCenter = Offset(width * 0.3f, height * 0.75f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        SunsetCloudPeach.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = bloomCenter,
                    radius = width * 0.6f
                ),
                center = bloomCenter,
                radius = width * 0.6f
            )
        }

        content()
    }
}

private data class ParticleData(
    val xPct: Float,
    val yPct: Float,
    val radius: Float,
    val alphaSeed: Float
)
