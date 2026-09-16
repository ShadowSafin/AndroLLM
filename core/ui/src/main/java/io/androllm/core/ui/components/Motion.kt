package io.androllm.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.scale
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
