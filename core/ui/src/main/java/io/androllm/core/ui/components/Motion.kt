package io.androllm.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.delay

/**
 * App-wide motion kit — heavy, springy, consistent.
 *
 * - [StaggeredEntrance]: list/screen items fade + rise in sequence, 70ms
 *   apart (capped), on a medium-bouncy spring. State is remembered, so items
 *   never replay on scroll or recomposition.
 * - [bounceClick]: press squash (0.96) with a bouncy release for any tappable
 *   surface that manages its own background.
 */

/** Delay step between consecutive items; total cascade capped via [maxDelayIndex]. */
const val StaggerStepMs = 50
const val StaggerMaxIndex = 8

@Composable
fun StaggeredEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    /**
     * Rows inside scrolling lists pass [instant] — no delay, no slide, just a
     * 150ms fade so recycled rows never pop or replay while scrolling.
     */
    instant: Boolean = false,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!instant) {
            delay((index.coerceIn(0, StaggerMaxIndex) * StaggerStepMs).toLong())
        }
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (instant) {
            fadeIn(animationSpec = tween(durationMillis = 150))
        } else {
            fadeIn(animationSpec = tween(durationMillis = 250)) +
                slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    initialOffsetY = { it / 6 }
                )
        },
        label = "staggeredEntrance"
    ) {
        content()
    }
}

/**
 * Press physics for custom tappables: squash to [pressedScale] while held,
 * spring back on release. Screen-reader role included; no ripple (the scale
 * IS the feedback).
 */
@Composable
fun Modifier.bounceClick(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bounceClick"
    )
    return this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick
        )
}


/**
 * Infinite breathing value between [min] and [max] on an ease-in-out loop.
 * Returns [min] statically when [enabled] is false (reduce-motion path).
 */
@Composable
fun rememberPulse(
    min: Float = 0.92f,
    max: Float = 1.08f,
    durationMs: Int = 2600,
    enabled: Boolean = true
): Float {
    if (!enabled) return min
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseValue"
    ).value
}

/**
 * A soft diagonal light band sweeping across the surface on a loop — the
 * loading shimmer for skeletons, and a quiet sheen for hero surfaces.
 * No-op when [enabled] is false.
 */
@Composable
fun Modifier.shimmer(
    enabled: Boolean = true,
    durationMs: Int = 1800,
    bandColor: Color = Color.White.copy(alpha = 0.10f)
): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerShift"
    ).value
    return drawWithContent {
        drawContent()
        val band = size.width * 0.45f
        val x = -band + (size.width + band * 2f) * shift
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, bandColor, Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + band, size.height)
            )
        )
    }
}

