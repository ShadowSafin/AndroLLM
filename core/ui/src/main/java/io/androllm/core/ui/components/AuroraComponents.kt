package io.androllm.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.ledger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Aurora surface kit — neon gradient light on the blackout ground.
 * Chrome stays monochrome; colour lives only in the light (orbs, accents,
 * gradient fills), never in the controls themselves.
 */

val AuroraViolet = Color(0xFF7A5CFF)
val AuroraBlue = Color(0xFF3D7BFF)
val AuroraCyan = Color(0xFF2DE1FC)
val AuroraMagenta = Color(0xFFF25DFF)
val AuroraEmerald = Color(0xFF2BF5A4)

/**
 * Deep-black ground with two enormous, softly blurred gradient orbs drifting
 * behind the content. Static, dimmed orbs under reduce-motion.
 */
@Composable
fun AuroraBackground(
    accentA: Color,
    accentB: Color,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val driftA: Float
    val driftB: Float
    if (reduceMotion) {
        driftA = 0f
        driftB = 0f
    } else {
        val transition = rememberInfiniteTransition(label = "aurora")
        driftA = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
            label = "auroraDriftA"
        ).value
        driftB = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Restart),
            label = "auroraDriftB"
        ).value
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.ledger.deskNight)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Orb 1 — rides high, swings gently side to side.
            val orbOne = Offset(
                x = w * 0.5f + cos(driftA) * w * 0.22f,
                y = h * 0.14f + sin(driftA) * h * 0.045f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentA.copy(alpha = 0.42f),
                        accentA.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = orbOne,
                    radius = w * 0.85f
                ),
                center = orbOne,
                radius = w * 0.85f
            )

            // Orb 2 — anchors the bottom edge, counter-rotating.
            val orbTwo = Offset(
                x = w * 0.5f + cos(driftB + 2.1f) * w * 0.26f,
                y = h * 0.92f + sin(driftB + 1.3f) * h * 0.04f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentB.copy(alpha = 0.34f),
                        accentB.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = orbTwo,
                    radius = w * 0.9f
                ),
                center = orbTwo,
                radius = w * 0.9f
            )

            // A whisper of moonlight between them for depth.
            val mid = Offset(w * 0.82f, h * 0.48f + sin(driftA * 0.7f) * h * 0.03f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                    center = mid,
                    radius = w * 0.5f
                ),
                center = mid,
                radius = w * 0.5f
            )
        }
        content()
    }
}
/**
 * The primary action: a tall white capsule, near-black label, trailing arrow,
 * and a heavy spring squash while pressed (the scale IS the feedback).
 */
@Composable
fun AuroraCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    brush: Brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFE8E8F0))),
    contentColor: Color = Color(0xFF0B0B10),
    icon: ImageVector? = Icons.AutoMirrored.Filled.ArrowForward,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(brush)
            .bounceClick(enabled = enabled, pressedScale = 0.95f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = contentColor,
                    fontWeight = FontWeight.Bold
                )
            )
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * A slim, rounded track whose gradient fill eases to [progress] on a soft
 * spring — quiet progress for multi-step flows.
 */
@Composable
fun AuroraProgressBar(
    progress: Float,
    brush: Brush,
    modifier: Modifier = Modifier,
    trackColor: Color = Color(0xFF1E1E24)
) {
    val animated = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "auroraProgress"
    ).value
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(CircleShape)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(CircleShape)
                .background(brush)
        )
    }
}

/** Frosted pill with a neon accent bead — metadata that glows, not shouts. */
@Composable
fun GlassChip(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
