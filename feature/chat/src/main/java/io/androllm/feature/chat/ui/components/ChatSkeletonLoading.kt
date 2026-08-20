package io.androllm.feature.chat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.ledger

/**
 * The chat's loading page — a shimmering skeleton of the conversation while
 * the engine warms up. Blocks breathe with the lamp; nothing jumps.
 */
@Composable
fun ChatSkeletonLoading(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "chatSkeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShimmer"
    )
    val ledger = MaterialTheme.ledger

    fun shimmerBrush(): Brush {
        val base = ledger.deskWalnutDeep
        val highlight = ledger.deskWalnutRaised
        return Brush.horizontalGradient(
            colors = listOf(base, highlight, base),
            startX = -300f + shimmer * 900f,
            endX = 300f + shimmer * 900f
        )
    }

    @Composable
    fun SkeletonBlock(
        widthFraction: Float,
        height: androidx.compose.ui.unit.Dp,
        shape: RoundedCornerShape
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(height)
                .clip(shape)
                .background(shimmerBrush())
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Assistant bubble.
        Column(
            modifier = Modifier.fillMaxWidth(0.92f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ledger.deskWalnutDeep)
                )
                Spacer(modifier = Modifier.width(10.dp))
                SkeletonBlock(0.18f, 12.dp, RoundedCornerShape(6.dp))
            }
            SkeletonBlock(1f, 14.dp, RoundedCornerShape(8.dp))
            SkeletonBlock(0.85f, 14.dp, RoundedCornerShape(8.dp))
            SkeletonBlock(0.55f, 14.dp, RoundedCornerShape(8.dp))
        }

        // User bubble (right-aligned).
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .align(Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SkeletonBlock(0.7f, 14.dp, RoundedCornerShape(18.dp))
            SkeletonBlock(0.42f, 14.dp, RoundedCornerShape(18.dp))
        }

        // Assistant bubble with code block hint.
        Column(
            modifier = Modifier.fillMaxWidth(0.92f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ledger.deskWalnutDeep)
                )
                Spacer(modifier = Modifier.width(10.dp))
                SkeletonBlock(0.24f, 12.dp, RoundedCornerShape(6.dp))
            }
            SkeletonBlock(0.9f, 14.dp, RoundedCornerShape(8.dp))
            SkeletonBlock(0.7f, 14.dp, RoundedCornerShape(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(ledger.deskWalnutDeep, ledger.deskWalnut, ledger.deskWalnutDeep),
                            startX = -300f + shimmer * 900f,
                            endX = 300f + shimmer * 900f
                        )
                    )
            )
        }
    }
}