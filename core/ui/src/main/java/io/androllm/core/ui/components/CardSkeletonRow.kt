package io.androllm.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.ledger

/**
 * Shimmer placeholder for dashboard cards while telemetry loads — a soft
 * breathing block in the desk's own walnut tones, so the skeleton never
 * fights the theme (light, dark or AMOLED).
 */
@Composable
fun CardSkeletonRow(
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    cornerRadius: Dp = 24.dp
) {
    val transition = rememberInfiniteTransition(label = "cardSkeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cardShimmer"
    )
    val ledger = MaterialTheme.ledger
    val brush = Brush.horizontalGradient(
        colors = listOf(ledger.deskWalnutDeep, ledger.deskWalnutRaised, ledger.deskWalnutDeep),
        startX = -300f + shimmer * 900f,
        endX = 300f + shimmer * 900f
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
            .height(height)
    )
}