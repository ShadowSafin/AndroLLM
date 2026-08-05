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
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskNight
import io.androllm.core.ui.theme.DeskNightRaised
import io.androllm.core.ui.theme.DeskWalnutDeep
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampHalo
import kotlin.random.Random

/**
 * The Writer's Night Desk background — the room around the lamp.
 *
 * A deep warm night ground, a slow breathing lamp pool high in the room, a
 * faint ruled horizon, and motes of dust drifting through the lamplight.
 * Calm and slow: the desk holds still while the model thinks.
 */
@Composable
fun CloudAtmosphericBackground(
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "DeskAtmosphereTransition")

    val lampBreath = if (reduceMotion) 0.5f else infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lampBreath"
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DeskNight,
                        DeskNightRaised,
                        DeskWalnutDeep
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Layer 1: the lamp pool high in the room — one warm light above the desk.
            val lampCenter = Offset(width * 0.72f, height * 0.06f)
            val glowRadius = width * 0.62f * (1f + lampBreath * 0.14f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        LampAmber.copy(alpha = 0.16f + lampBreath * 0.08f),
                        LampHalo.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = lampCenter,
                    radius = glowRadius
                ),
                center = lampCenter,
                radius = glowRadius
            )

            // Layer 2: a faint counter-light low on the desk (the screen's own glow).
            val deskGlowCenter = Offset(width * 0.28f, height * 0.94f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        LampAmber.copy(alpha = 0.07f),
                        Color.Transparent
                    ),
                    center = deskGlowCenter,
                    radius = width * 0.55f
                ),
                center = deskGlowCenter,
                radius = width * 0.55f
            )

            // Layer 3: the ruled horizon — the desk edge receding into the dark.
            val horizonY = height * 0.82f
            drawLine(
                color = DeskHairline.copy(alpha = 0.55f),
                start = Offset(0f, horizonY),
                end = Offset(width, horizonY),
                strokeWidth = 1.2f
            )
            drawLine(
                color = DeskInkFaint.copy(alpha = 0.18f),
                start = Offset(0f, horizonY + 14f),
                end = Offset(width, horizonY + 14f),
                strokeWidth = 1f
            )

            // Layer 4: dust drifting through the lamplight.
            dust.forEach { mote ->
                val floatY = ((mote.yPct - dustShift / 360f * 0.08f) % 1f + 1f) % 1f
                val x = mote.xPct * width
                val y = floatY * height
                val twinkle = (0.12f + 0.3f * kotlin.math.sin(dustShift * 0.04f + mote.seed * 9f))
                    .coerceIn(0.06f, 0.4f)
                drawCircle(
                    color = LampAmber.copy(alpha = twinkle),
                    radius = mote.radius,
                    center = Offset(x, y)
                )
            }
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