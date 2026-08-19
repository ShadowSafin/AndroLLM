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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.theme.LedgerColors
import io.androllm.core.ui.theme.ledger
import androidx.compose.material3.MaterialTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motion values (bob / drift / pulse / phase) shared by the scenes.
 * Every value is static when reduce-motion is enabled.
 */
private class SceneMotion(
    val bob: Float,
    val drift: Float,
    val pulse: Float,
    val phase: Float
)

@Composable
private fun rememberSceneMotion(reduceMotion: Boolean): SceneMotion {
    if (reduceMotion) {
        return remember { SceneMotion(bob = 0f, drift = 0f, pulse = 1f, phase = 0f) }
    }
    val transition = rememberInfiniteTransition(label = "SceneMotion")
    return SceneMotion(
        bob = transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse),
            label = "bob"
        ).value,
        drift = transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
            label = "drift"
        ).value,
        pulse = transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse"
        ).value,
        phase = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase"
        ).value
    )
}

// ── shared drawing helpers ───────────────────────────────────────────────────

private inline fun DrawScope.radialGlow(
    ledger: LedgerColors,
    center: Offset,
    radius: Float,
    color: Color
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.5f), color.copy(alpha = 0.15f), Color.Transparent)
        ),
        radius = radius,
        center = center
    )
}

private inline fun DrawScope.drawCrescentMoon(
    ledger: LedgerColors,
    center: Offset,
    radius: Float,
    color: Color
) {
    radialGlow(ledger, center, radius * 2.5f, color)
    val moonPath = Path().apply { addOval(Rect(center, radius)) }
    val cutoutPath = Path().apply {
        addOval(Rect(Offset(center.x - radius * 0.4f, center.y - radius * 0.3f), radius * 0.9f))
    }
    drawPath(
        path = Path.combine(PathOperation.Difference, moonPath, cutoutPath),
        brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), ledger.lampDeep))
    )
}

/** The night desk surface — a warm walnut band along the bottom. */
private inline fun DrawScope.drawDeskBand(
    ledger: LedgerColors,
    width: Float,
    height: Float,
    topFraction: Float = 0.82f
) {
    val top = height * topFraction
    drawRect(
        brush = Brush.verticalGradient(
            listOf(ledger.deskWalnutRaised, ledger.deskWalnutDeep)
        ),
        topLeft = Offset(0f, top),
        size = Size(width, height - top)
    )
    drawLine(
        color = ledger.deskHairline,
        start = Offset(0f, top),
        end = Offset(width, top),
        strokeWidth = height * 0.012f,
        cap = StrokeCap.Round
    )
}

/** A standing desk lamp: arm, pole and warm amber shade. */
private inline fun DrawScope.drawLamp(
    ledger: LedgerColors,
    baseX: Float,
    shadeY: Float,
    size: Float,
    pulse: Float,
    moon: Boolean
) {
    // moonlight coming in the window, kept warm by the lamp
    if (moon) {
        drawCrescentMoon(ledger, Offset(baseX, shadeY * 0.45f), size * 0.28f, ledger.lampGlow)
    }
    // halo under the lamp
    radialGlow(ledger, Offset(baseX, shadeY), size * 3.4f * pulse, ledger.lampAmber)
    // shade
    val shadeW = size * 0.9f
    val shade = Path().apply {
        moveTo(baseX - shadeW * 0.5f, shadeY)
        lineTo(baseX - shadeW * 0.34f, shadeY - size * 0.55f)
        lineTo(baseX + shadeW * 0.34f, shadeY - size * 0.55f)
        lineTo(baseX + shadeW * 0.5f, shadeY)
        close()
    }
    drawPath(
        path = shade,
        brush = Brush.verticalGradient(listOf(ledger.lampGlow, ledger.lampAmber)),
    )
    // bulb glow at the mouth of the shade
    drawCircle(ledger.lampGlow.copy(alpha = 0.6f * pulse), radius = size * 0.22f, center = Offset(baseX, shadeY))
    // stem
    drawLine(
        color = ledger.deskWalnutRaised,
        start = Offset(baseX, shadeY),
        end = Offset(baseX, shadeY * 1.35f),
        strokeWidth = size * 0.07f,
        cap = StrokeCap.Round
    )
    // base + pool of light on the desk
    drawOval(
        brush = Brush.radialGradient(
            listOf(ledger.lampAmber.copy(alpha = 0.30f), Color.Transparent)
        ),
        topLeft = Offset(baseX - size * 0.8f, shadeY * 1.3f),
        size = Size(size * 1.6f, size * 0.42f)
    )
}

// ── Page 1 · Welcome ─────────────────────────────────────────────────────────

@Composable
fun WelcomeScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    val ledger = MaterialTheme.ledger
    Box(modifier = modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawDeskBand(ledger, w, h)
            drawLamp(ledger, baseX = w * 0.24f, shadeY = h * 0.42f, size = w * 0.34f, pulse = m.pulse, moon = true)
        }
        CloudBugdroidLogo(
            size = 118.dp,
            modifier = Modifier.graphicsLayer { translationY = m.bob * 7f }
        )
    }
}

// ── Page 2 · The Desk Keeps Everything ───────────────────────────────────────

@Composable
fun LocalScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    val ledger = MaterialTheme.ledger
    Canvas(modifier = modifier.fillMaxWidth().height(230.dp)) {
        val w = size.width
        val h = size.height
        val deskTop = h * 0.82f
        drawDeskBand(ledger, w, h, topFraction = deskTop / h)

        val cx = w * 0.5f
        val cy = h * 0.52f
        val devW = w * 0.46f
        val devH = h * 0.62f

        // Crossed-out cloud: "no wires out" — the old cloudy way, crossed out
        val cloudX = w * 0.26f
        val cloudY = h * 0.22f
        drawCircle(ledger.deskInkFaint.copy(alpha = 0.6f), radius = w * 0.05f, center = Offset(cloudX - w * 0.06f, cloudY + w * 0.015f))
        drawCircle(ledger.deskInkFaint.copy(alpha = 0.6f), radius = w * 0.062f, center = Offset(cloudX, cloudY - w * 0.015f))
        drawCircle(ledger.deskInkFaint.copy(alpha = 0.6f), radius = w * 0.05f, center = Offset(cloudX + w * 0.06f, cloudY + w * 0.015f))
        val slash = w * 0.10f
        drawLine(
            color = ledger.lampGlow,
            start = Offset(cloudX - slash, cloudY - slash),
            end = Offset(cloudX + slash, cloudY + slash),
            strokeWidth = w * 0.022f,
            cap = StrokeCap.Round
        )

        // Device shell — a walnut-bound notebook
        val corner = devW * 0.16f
        drawRoundRect(
            brush = Brush.linearGradient(listOf(ledger.deskWalnutRaised, ledger.deskWalnut)),
            topLeft = Offset(cx - devW / 2, cy - devH / 2),
            size = Size(devW, devH),
            cornerRadius = CornerRadius(corner)
        )
        drawRoundRect(
            color = ledger.deskHairline,
            topLeft = Offset(cx - devW / 2, cy - devH / 2),
            size = Size(devW, devH),
            cornerRadius = CornerRadius(corner),
            style = Stroke(w * 0.012f)
        )

        // Screen — a ruled paper page
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(ledger.deskNight, ledger.deskWalnutDeep)),
            topLeft = Offset(cx - devW * 0.4f, cy - devH * 0.38f),
            size = Size(devW * 0.8f, devH * 0.66f),
            cornerRadius = CornerRadius(devW * 0.07f)
        )

        // Glowing on-device core — the lamp inside the notebook
        val coreX = cx
        val coreY = cy + devH * 0.02f
        radialGlow(ledger, Offset(coreX, coreY), devW * 0.36f * m.pulse, ledger.lampGlow)
        drawCircle(ledger.lampGlow.copy(alpha = 0.9f), radius = devW * 0.085f, center = Offset(coreX, coreY))
        drawCircle(ledger.lampAmber.copy(alpha = 0.5f), radius = devW * 0.05f, center = Offset(coreX, coreY))

        // Orbiting particles around the core
        repeat(3) { i ->
            val angle = m.phase * 2f * PI.toFloat() + i * 2.09f
            val ox = coreX + cos(angle) * devW * 0.2f
            val oy = coreY + sin(angle) * devW * 0.2f
            drawCircle(ledger.lampGlow.copy(alpha = 0.8f), radius = devW * 0.018f, center = Offset(ox, oy))
        }

        // Floating ink dots rising from the notebook
        repeat(4) { i ->
            val t = ((m.phase + i * 0.25f) % 1f)
            val x = coreX + sin((i * 1.7f) + m.phase * 6f) * devW * 0.22f
            val y = cy - devH * 0.36f - t * devH * 0.5f
            drawCircle(
                ledger.lampAmber.copy(alpha = 0.7f * (1f - t)),
                radius = w * 0.012f,
                center = Offset(x, y)
            )
        }
    }
}

// ── Page 3 · Lightning by Lamplight ──────────────────────────────────────────

@Composable
fun LightningScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    val ledger = MaterialTheme.ledger
    Canvas(modifier = modifier.fillMaxWidth().height(230.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.5f
        val chipW = w * 0.34f
        val chipH = h * 0.30f

        // Speed lines radiating behind the chip
        val speed = m.phase * 2f * PI.toFloat()
        repeat(6) { i ->
            val angle = i * (PI.toFloat() / 3f) + 0.3f
            val outer = chipW * (0.75f + 0.22f * sin(speed * 0.5f + i))
            drawLine(
                color = ledger.lampDeep.copy(alpha = 0.5f),
                start = Offset(cx + cos(angle) * chipW * 0.5f, cy + sin(angle) * chipH * 0.5f),
                end = Offset(cx + cos(angle) * outer, cy + sin(angle) * outer),
                strokeWidth = w * 0.012f,
                cap = StrokeCap.Round
            )
        }

        // GPU chip outline + pins
        drawRoundRect(
            color = ledger.deskInk.copy(alpha = 0.8f),
            topLeft = Offset(cx - chipW / 2, cy - chipH / 2),
            size = Size(chipW, chipH),
            cornerRadius = CornerRadius(chipW * 0.12f),
            style = Stroke(w * 0.014f)
        )
        val pin = w * 0.012f
        repeat(3) { i ->
            val px = cx - chipW / 2 + chipW * (0.3f + i * 0.2f)
            drawLine(ledger.lampGlow.copy(alpha = 0.8f), Offset(px, cy - chipH / 2 - w * 0.03f), Offset(px, cy - chipH / 2), strokeWidth = pin, cap = StrokeCap.Round)
            drawLine(ledger.lampGlow.copy(alpha = 0.8f), Offset(px, cy + chipH / 2), Offset(px, cy + chipH / 2 + w * 0.03f), strokeWidth = pin, cap = StrokeCap.Round)
        }

        // Inner die
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(ledger.deskWalnutRaised, ledger.deskWalnutDeep)),
            topLeft = Offset(cx - chipW * 0.32f, cy - chipH * 0.32f),
            size = Size(chipW * 0.64f, chipH * 0.64f),
            cornerRadius = CornerRadius(chipW * 0.07f)
        )

        // Lightning bolt — struck by lamp
        radialGlow(ledger, Offset(cx, cy), chipW * 0.42f * m.pulse, ledger.lampGlow)
        val bolt = Path().apply {
            val bW = chipW * 0.42f
            val bH = chipH * 0.5f
            moveTo(cx + bW * 0.3f, cy - bH)
            lineTo(cx - bW * 0.55f, cy + bH * 0.15f)
            lineTo(cx - bW * 0.05f, cy + bH * 0.15f)
            lineTo(cx - bW * 0.3f, cy + bH)
            lineTo(cx + bW * 0.6f, cy - bH * 0.12f)
            lineTo(cx + bW * 0.12f, cy - bH * 0.12f)
            close()
        }
        drawPath(
            path = bolt,
            brush = Brush.verticalGradient(listOf(ledger.lampGlow, ledger.lampDeep))
        )

        // Streaming token dots along a sine wave
        val streamY = cy + chipH * 0.9f
        repeat(9) { i ->
            val t = (m.phase + i / 9f) % 1f
            val x = -w * 0.1f + t * w * 1.2f
            val y = streamY + sin(t * 3.2f) * h * 0.05f
            drawCircle(
                color = if (i % 3 == 0) ledger.lampGlow.copy(alpha = 0.9f) else ledger.deskPaperDim.copy(alpha = 0.6f),
                radius = w * 0.014f,
                center = Offset(x, y)
            )
        }
        drawLine(
            color = ledger.deskHairline.copy(alpha = 0.7f),
            start = Offset(w * 0.06f, streamY),
            end = Offset(w * 0.94f, streamY),
            strokeWidth = w * 0.006f,
            cap = StrokeCap.Round
        )
    }
}

// ── Page 4 · Choose Your Models ──────────────────────────────────────────────

@Composable
fun ModelsScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    val ledger = MaterialTheme.ledger
    Canvas(modifier = modifier.fillMaxWidth().height(230.dp)) {
        val w = size.width
        val h = size.height
        drawDeskBand(ledger, w, h)
        val cx = w * 0.5f
        val baseY = h * 0.60f
        val cardW = w * 0.52f
        val cardH = h * 0.16f

        // Back stack slips
        listOf(0.62f, 0.78f).forEachIndexed { i, f ->
            val yOff = (i + 1) * h * 0.05f
            drawRoundRect(
                brush = Brush.linearGradient(listOf(ledger.deskWalnutRaised.copy(alpha = 0.7f), ledger.deskWalnut.copy(alpha = 0.5f))),
                topLeft = Offset(cx - cardW * f / 2, baseY - cardH - yOff),
                size = Size(cardW * f, cardH),
                cornerRadius = CornerRadius(cardW * 0.06f)
            )
            drawRoundRect(
                color = ledger.deskHairline.copy(alpha = 0.6f),
                topLeft = Offset(cx - cardW * f / 2, baseY - cardH - yOff),
                size = Size(cardW * f, cardH),
                cornerRadius = CornerRadius(cardW * 0.06f),
                style = Stroke(w * 0.008f)
            )
        }

        // Front slip (walnut)
        drawRoundRect(
            brush = Brush.linearGradient(listOf(ledger.deskWalnutRaised, ledger.deskWalnut)),
            topLeft = Offset(cx - cardW / 2, baseY - cardH),
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(cardW * 0.07f)
        )
        drawRoundRect(
            color = ledger.deskHairline,
            topLeft = Offset(cx - cardW / 2, baseY - cardH),
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(cardW * 0.07f),
            style = Stroke(w * 0.01f)
        )

        // Miniature model label — amber seal on ink lines
        drawCircle(ledger.lampAmber.copy(alpha = 0.3f), radius = cardH * 0.22f, center = Offset(cx - cardW * 0.34f, baseY - cardH * 0.5f))
        drawLine(
            color = ledger.deskPaper.copy(alpha = 0.5f),
            start = Offset(cx - cardW * 0.22f, baseY - cardH * 0.52f),
            end = Offset(cx + cardW * 0.3f, baseY - cardH * 0.52f),
            strokeWidth = cardH * 0.06f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = ledger.deskPaperDim.copy(alpha = 0.4f),
            start = Offset(cx - cardW * 0.22f, baseY - cardH * 0.32f),
            end = Offset(cx + cardW * 0.16f, baseY - cardH * 0.32f),
            strokeWidth = cardH * 0.06f,
            cap = StrokeCap.Round
        )

        // Progress ring + check on the right
        val ringC = Offset(cx + cardW * 0.34f, baseY - cardH * 0.5f)
        val ringR = cardH * 0.36f
        drawArc(
            color = ledger.lampDeep.copy(alpha = 0.5f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(ringC.x - ringR, ringC.y - ringR),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(cardH * 0.09f, cap = StrokeCap.Round)
        )
        drawArc(
            color = ledger.lampGlow,
            startAngle = -90f,
            sweepAngle = 300f * m.pulse.coerceIn(0.9f, 1f),
            useCenter = false,
            topLeft = Offset(ringC.x - ringR, ringC.y - ringR),
            size = Size(ringR * 2, ringR * 2),
            style = Stroke(cardH * 0.09f, cap = StrokeCap.Round)
        )
        val check = Path().apply {
            moveTo(ringC.x - ringR * 0.42f, ringC.y)
            lineTo(ringC.x - ringR * 0.1f, ringC.y + ringR * 0.38f)
            lineTo(ringC.x + ringR * 0.5f, ringC.y - ringR * 0.34f)
        }
        drawPath(check, color = ledger.lampGlow, style = Stroke(cardH * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Download sparkles
        repeat(3) { i ->
            val t = ((m.phase + i * 0.33f) % 1f)
            val x = cx + cos((i * 2.1f)) * cardW * 0.6f
            val y = baseY - cardH - t * h * 0.16f
            drawCircle(ledger.lampGlow.copy(alpha = 0.7f * (1f - t)), radius = w * 0.012f, center = Offset(x, y))
        }
    }
}

// ── Page 5 · Ready to Begin ──────────────────────────────────────────────────

@Composable
fun ReadyScene(modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val m = rememberSceneMotion(reduceMotion)
    val ledger = MaterialTheme.ledger
    Box(modifier = modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.48f
            drawDeskBand(ledger, w, h)
            drawCrescentMoon(ledger, Offset(cx, h * 0.18f), w * 0.09f, ledger.lampGlow)
            radialGlow(ledger, Offset(cx, cy), w * 0.36f * m.pulse, ledger.lampGlow)
            // Orbiting sparkles
            repeat(5) { i ->
                val angle = m.phase * 2f * PI.toFloat() + i * 1.256f
                val r = w * 0.34f
                drawCircle(
                    ledger.lampGlow.copy(alpha = 0.65f),
                    radius = w * 0.014f,
                    center = Offset(cx + cos(angle) * r, cy + sin(angle) * r * 0.6f)
                )
            }
        }
        CloudBugdroidLogo(
            size = 140.dp,
            modifier = Modifier.graphicsLayer { translationY = m.bob * 8f }
        )
    }
}