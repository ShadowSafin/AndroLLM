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
import androidx.compose.ui.graphics.luminance
import kotlin.random.Random
import io.androllm.core.ui.theme.ledger
import androidx.compose.material3.MaterialTheme

/**
 * The Aurora desk background — the shared app ground.
 *
 * Pure-black ground with two drifting violet/magenta gradient orbs (the
 * onboarding look, everywhere), a breathing pool of light, a faint ruled
 * horizon, and motes of dust drifting through the air. Calm and slow: the
 * desk holds still while the model thinks.
 */
@Composable
fun CloudAtmosphericBackground(
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    showDots: Boolean = true,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ParchmentAtmosphereTransition")

    val sunBreath = if (reduceMotion) 0.5f else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sunBreath"
    ).value

    val dustShift = if (reduceMotion) 0f else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dustShift"
    ).value

    val dust = remember {
        List(22) {
            DustMote(
                xPct = Random.nextFloat(),
                yPct = Random.nextFloat(),
                radius = Random.nextFloat() * 1.6f + 0.6f,
                seed = Random.nextFloat()
            )
        }
    }

    val ledger = MaterialTheme.ledger
    val isDark = ledger.deskNight.luminance() < 0.5f
    val deskNight = ledger.deskNight
    val deskNightRaised = ledger.deskNightRaised
    val deskWalnutDeep = ledger.deskWalnutDeep
    val lampAmber = ledger.lampAmber
    val lampDeep = ledger.lampDeep
    val lampHalo = ledger.lampHalo
    val deskHairline = ledger.deskHairline
    val deskInkFaint = ledger.deskInkFaint
    // The pool of light reads as a violet breath on the blackout desk:
    // kept whisper-quiet so content stays crisp, never foggy.
    val sunGlowAlpha = if (isDark) 0.14f + sunBreath * 0.05f else 0.12f + sunBreath * 0.04f
    val deskGlowAlpha = if (isDark) 0.08f else 0.07f
    val dustTwinkleRange = if (isDark) 0.05f to 0.18f else 0.04f to 0.16f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        deskNight,
                        deskNightRaised,
                        deskWalnutDeep
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Layer 0: the aurora orbs — violet riding high, magenta low.
            val orbDrift = dustShift * (6.283185f / 360f)
            val violetOrb = Offset(
                x = width * 0.62f + kotlin.math.cos(orbDrift) * width * 0.16f,
                y = height * 0.10f + kotlin.math.sin(orbDrift) * height * 0.03f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lampAmber.copy(alpha = if (isDark) 0.22f else 0.18f),
                        lampAmber.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = violetOrb,
                    radius = width * 0.72f
                ),
                center = violetOrb,
                radius = width * 0.72f
            )
            val magentaOrb = Offset(
                x = width * 0.30f + kotlin.math.cos(orbDrift * 0.8f + 2.4f) * width * 0.18f,
                y = height * 0.88f + kotlin.math.sin(orbDrift * 0.8f + 1.1f) * height * 0.03f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lampDeep.copy(alpha = if (isDark) 0.16f else 0.12f),
                        lampDeep.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = magentaOrb,
                    radius = width * 0.78f
                ),
                center = magentaOrb,
                radius = width * 0.78f
            )

            // Layer 1: the violet pool of light high in the room.
            val sunCenter = Offset(width * 0.72f, height * 0.06f)
            val glowRadius = width * 0.62f * (1f + sunBreath * 0.14f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lampAmber.copy(alpha = sunGlowAlpha),
                        lampHalo.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = sunCenter,
                    radius = glowRadius
                ),
                center = sunCenter,
                radius = glowRadius
            )

            // Layer 2: a faint counter-light low on the desk (the page's own glow).
            val deskGlowCenter = Offset(width * 0.28f, height * 0.94f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lampAmber.copy(alpha = deskGlowAlpha),
                        Color.Transparent
                    ),
                    center = deskGlowCenter,
                    radius = width * 0.55f
                ),
                center = deskGlowCenter,
                radius = width * 0.55f
            )

            // Layer 3: the ruled horizon — the desk edge receding into the page.
            val horizonY = height * 0.82f
            drawLine(
                color = deskHairline.copy(alpha = if (isDark) 0.5f else 0.7f),
                start = Offset(0f, horizonY),
                end = Offset(width, horizonY),
                strokeWidth = 1.2f
            )
            drawLine(
                color = deskInkFaint.copy(alpha = if (isDark) 0.16f else 0.22f),
                start = Offset(0f, horizonY + 14f),
                end = Offset(width, horizonY + 14f),
                strokeWidth = 1f
            )

            // Layer 4: dust drifting through the sunlit air.
            dust.forEach { mote ->
                val floatY = ((mote.yPct - dustShift / 360f * 0.08f) % 1f + 1f) % 1f
                val x = mote.xPct * width
                val y = floatY * height
                val twinkle = (0.12f + 0.3f * kotlin.math.sin(dustShift * 0.04f + mote.seed * 9f))
                    .coerceIn(dustTwinkleRange.first, dustTwinkleRange.second)
                drawCircle(
                    color = lampAmber.copy(alpha = twinkle),
                    radius = mote.radius,
                    center = Offset(x, y)
                )
            }
        }

        // Layer 5: the sign-in dot grid — dimmed to a backdrop whisper so
        // text stays crisp over it. White on the blackout desk.
        if (showDots) {
            DotGridBackground(
                modifier = Modifier.fillMaxSize(),
                dotColor = if (isDark) Color(0xFFCFC2FF) else deskInkFaint,
                alphaScale = if (isDark) 0.5f else 0.55f,
                animate = !reduceMotion
            )
        }

        content()
    }
}

private data class DustMote(
    val xPct: Float,
    val yPct: Float,
    val radius: Float,
    val seed: Float
)
