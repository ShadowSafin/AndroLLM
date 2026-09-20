package io.androllm.feature.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.components.AuroraBlue
import io.androllm.core.ui.components.AuroraCyan
import io.androllm.core.ui.components.AuroraEmerald
import io.androllm.core.ui.components.AuroraMagenta
import io.androllm.core.ui.components.AuroraViolet
import io.androllm.core.ui.components.CloudBugdroidLogo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated neon-on-black scenes for the onboarding flow. Every scene draws
 * with the Aurora accent light against the deep-black ground; all motion is
 * static under reduce-motion.
 */

private class SceneMotion(
    val bob: Float,
    val pulse: Float,
    val phase: Float
)

@Composable
private fun rememberSceneMotion(reduceMotion: Boolean): SceneMotion {
    if (reduceMotion) return remember { SceneMotion(bob = 0f, pulse = 1f, phase = 0f) }
    val t = rememberInfiniteTransition(label = "scene")
    return SceneMotion(
        bob = t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Reverse), "bob").value,
        pulse = t.animateFloat(0.86f, 1.14f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), "pulse").value,
        phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), "phase").value
    )
}

private inline fun DrawScope.glow(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.16f), Color.Transparent),
            center = center,
            radius = radius
        ),
        center = center,
        radius = radius
    )
}

// -- Page 1 · Welcome --

@Composable
fun WelcomeScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    Box(modifier = modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Offset(w * 0.5f, h * 0.5f)

            glow(c, w * 0.42f * m.pulse, AuroraViolet)

            // Two counter-rotating elliptical orbits with satellites
            repeat(2) { i ->
                val spin = m.phase * 2f * PI.toFloat() * (if (i == 0) 1f else -1f)
                val r = w * (0.30f + i * 0.07f)
                drawOval(
                    color = (if (i == 0) AuroraViolet else AuroraCyan).copy(alpha = 0.35f),
                    topLeft = Offset(c.x - r, c.y - r * 0.42f),
                    size = Size(r * 2f, r * 0.84f),
                    style = Stroke(w * 0.006f)
                )
                val a = spin + i * PI.toFloat()
                drawCircle(
                    color = (if (i == 0) AuroraViolet else AuroraCyan).copy(alpha = 0.9f),
                    radius = w * 0.014f,
                    center = Offset(c.x + cos(a) * r, c.y + sin(a) * r * 0.42f)
                )
            }
        }
        CloudBugdroidLogo(
            size = 120.dp,
            modifier = Modifier.graphicsLayer { translationY = m.bob * 10f }
        )
    }
}

// -- Page 2 · Privacy --

@Composable
fun LocalScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    Canvas(modifier = modifier.fillMaxWidth().height(280.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.48f
        val shieldW = w * 0.44f
        val shieldH = h * 0.62f

        glow(Offset(cx, cy), w * 0.44f * m.pulse, AuroraEmerald)

        // Shield body
        val shield = Path().apply {
            moveTo(cx, cy - shieldH * 0.5f)
            cubicTo(
                cx + shieldW * 0.5f, cy - shieldH * 0.34f,
                cx + shieldW * 0.5f, cy - shieldH * 0.05f,
                cx + shieldW * 0.5f, cy + shieldH * 0.10f
            )
            cubicTo(
                cx + shieldW * 0.5f, cy + shieldH * 0.36f,
                cx + shieldW * 0.2f, cy + shieldH * 0.46f,
                cx, cy + shieldH * 0.55f
            )
            cubicTo(
                cx - shieldW * 0.2f, cy + shieldH * 0.46f,
                cx - shieldW * 0.5f, cy + shieldH * 0.36f,
                cx - shieldW * 0.5f, cy + shieldH * 0.10f
            )
            cubicTo(
                cx - shieldW * 0.5f, cy - shieldH * 0.05f,
                cx - shieldW * 0.5f, cy - shieldH * 0.34f,
                cx, cy - shieldH * 0.5f
            )
            close()
        }
        drawPath(shield, brush = Brush.verticalGradient(listOf(Color(0xFF101018), Color(0xFF0A0A10))))
        drawPath(
            shield,
            color = AuroraEmerald.copy(alpha = 0.8f),
            style = Stroke(w * 0.008f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Padlock
        val lockW = shieldW * 0.4f
        val lockH = shieldH * 0.26f
        val lockTop = cy - lockH * 0.25f
        drawArc(
            color = AuroraEmerald,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - lockW * 0.28f, lockTop - lockH * 0.55f),
            size = Size(lockW * 0.56f, lockH * 1.1f),
            style = Stroke(w * 0.014f, cap = StrokeCap.Round)
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(AuroraEmerald, AuroraEmerald.copy(alpha = 0.7f))),
            topLeft = Offset(cx - lockW / 2f, lockTop),
            size = Size(lockW, lockH),
            cornerRadius = CornerRadius(lockW * 0.18f)
        )
        drawCircle(Color(0xFF0B0B10), radius = lockW * 0.09f, center = Offset(cx, lockTop + lockH * 0.45f))

        // Data motes spiralling inward and absorbed
        repeat(6) { i ->
            val t = ((m.phase + i / 6f) % 1f)
            val angle = i * (2f * PI / 6f).toFloat() + m.phase * 2f
            val orbitR = w * 0.34f * (1f - t * 0.5f)
            drawCircle(
                AuroraEmerald.copy(alpha = 0.9f * (1f - t)),
                radius = w * 0.011f,
                center = Offset(cx + cos(angle) * orbitR, cy + sin(angle) * orbitR * 0.55f)
            )
        }
    }
}

// -- Page 3 · Speed --

@Composable
fun LightningScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    Canvas(modifier = modifier.fillMaxWidth().height(280.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val boltH = h * 0.52f
        val boltW = boltH * 0.62f

        glow(Offset(cx, cy), w * 0.40f * m.pulse, AuroraCyan)

        // Expanding speed rings
        repeat(3) { i ->
            val t = ((m.phase + i / 3f) % 1f)
            val r = w * 0.14f + t * w * 0.30f
            drawCircle(
                color = AuroraCyan.copy(alpha = 0.5f * (1f - t)),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(w * 0.005f)
            )
        }

        // The bolt
        val bolt = Path().apply {
            moveTo(cx + boltW * 0.12f, cy - boltH * 0.5f)
            lineTo(cx - boltW * 0.5f, cy + boltH * 0.10f)
            lineTo(cx - boltW * 0.06f, cy + boltH * 0.10f)
            lineTo(cx - boltW * 0.12f, cy + boltH * 0.5f)
            lineTo(cx + boltW * 0.5f, cy - boltH * 0.12f)
            lineTo(cx + boltW * 0.06f, cy - boltH * 0.12f)
            close()
        }
        drawPath(
            bolt,
            brush = Brush.verticalGradient(listOf(Color.White, AuroraCyan, AuroraBlue))
        )
        drawPath(
            bolt,
            color = Color.White.copy(alpha = 0.5f),
            style = Stroke(w * 0.006f, join = StrokeJoin.Round)
        )

        // Streaming sparks rising
        repeat(5) { i ->
            val t = ((m.phase * 1.6f + i * 0.2f) % 1f)
            val x = cx + (i - 2) * w * 0.12f
            val y = cy + h * 0.34f - t * h * 0.68f
            drawCircle(
                AuroraCyan.copy(alpha = 0.8f * (1f - t)),
                radius = w * 0.010f,
                center = Offset(x, y)
            )
        }
    }
}

// -- Page 4 · Models --

@Composable
fun ModelsScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    Canvas(modifier = modifier.fillMaxWidth().height(280.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.52f
        val cardW = w * 0.56f
        val cardH = h * 0.24f

        glow(Offset(cx, cy), w * 0.42f * m.pulse, AuroraBlue)

        // Three stacked cards, back to front, gently counter-bobbing
        repeat(3) { i ->
            val depth = 2 - i
            val bobOffset = m.bob * (4f + i * 5f) * (if (i % 2 == 0) 1f else -1f)
            val cardCx = cx + (depth * w * 0.05f) - w * 0.05f
            val cardCy = cy + (depth * h * 0.14f) - h * 0.16f + bobOffset
            val accent = when (i) {
                0 -> AuroraBlue
                1 -> AuroraViolet
                else -> AuroraMagenta
            }
            val corner = CornerRadius(cardH * 0.22f)
            drawRoundRect(
                brush = Brush.linearGradient(listOf(Color(0xFF14141C), Color(0xFF0C0C12))),
                topLeft = Offset(cardCx - cardW / 2f, cardCy - cardH / 2f),
                size = Size(cardW, cardH),
                cornerRadius = corner
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.45f - depth * 0.1f),
                topLeft = Offset(cardCx - cardW / 2f, cardCy - cardH / 2f),
                size = Size(cardW, cardH),
                cornerRadius = corner,
                style = Stroke(w * 0.005f)
            )
            drawCircle(
                color = accent,
                radius = cardH * 0.14f,
                center = Offset(cardCx - cardW * 0.34f, cardCy)
            )
            drawLine(
                color = Color.White.copy(alpha = 0.6f - depth * 0.15f),
                start = Offset(cardCx - cardW * 0.18f, cardCy - cardH * 0.16f),
                end = Offset(cardCx + cardW * 0.30f, cardCy - cardH * 0.16f),
                strokeWidth = cardH * 0.10f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White.copy(alpha = 0.3f - depth * 0.08f),
                start = Offset(cardCx - cardW * 0.18f, cardCy + cardH * 0.16f),
                end = Offset(cardCx + cardW * 0.16f, cardCy + cardH * 0.16f),
                strokeWidth = cardH * 0.08f,
                cap = StrokeCap.Round
            )
        }
    }
}

// -- Page 5 · Ready --

@Composable
fun ReadyScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    Box(modifier = modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Offset(w * 0.5f, h * 0.5f)

            glow(c, w * 0.44f * m.pulse, AuroraMagenta)

            // Radiating concentric rings
            repeat(4) { i ->
                val t = ((m.phase + i / 4f) % 1f)
                val r = w * 0.16f + t * w * 0.30f
                drawCircle(
                    color = AuroraMagenta.copy(alpha = 0.55f * (1f - t)),
                    radius = r,
                    center = c,
                    style = Stroke(w * 0.005f)
                )
            }

            // Confetti motes drifting upward
            repeat(8) { i ->
                val t = ((m.phase * 1.3f + i * 0.13f) % 1f)
                val drift = sin(m.phase * 6f + i * 1.7f) * w * 0.02f
                val x = w * (0.2f + (i % 5) * 0.15f) + drift
                val y = h * 0.9f - t * h * 0.8f
                val color = when (i % 3) {
                    0 -> AuroraMagenta
                    1 -> AuroraViolet
                    else -> AuroraCyan
                }
                drawCircle(
                    color.copy(alpha = 0.75f * (1f - t)),
                    radius = w * 0.009f,
                    center = Offset(x, y)
                )
            }
        }
        CloudBugdroidLogo(
            size = 120.dp,
            modifier = Modifier.graphicsLayer { translationY = m.bob * 10f }
        )
    }
}
