package io.androllm.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.AndroLLMTheme
import io.androllm.core.ui.theme.ledger
import kotlinx.coroutines.delay

/** Tone of the hero delta line: up (good), down (bad), or neutral (idle). */
enum class StatsDeltaTone {
    UP, DOWN, NEUTRAL
}

/** One muted sub-stat tile: big value + inline label, small caption below. */
data class StatsCardSubStat(
    val value: String,
    val label: String,
    val subLabel: String
)

/**
 * Session stats hero — a native port of the `stats-card` pattern:
 *
 * header (title + timeframe chip) → hero metric with delta → 2-up muted
 * sub-stats → highlighted rank banner → staggered availability bar chart.
 *
 * All content arrives as plain props (like the React component), so any
 * feature screen can map its own telemetry onto it. Bars animate in with a
 * 50ms stagger and re-animate smoothly when their level changes.
 */
@Composable
fun DeveloperStatsCard(
    title: String,
    timeFrame: String,
    heroLabel: String,
    heroValue: String,
    heroDelta: String,
    heroDeltaTone: StatsDeltaTone,
    subStats: List<StatsCardSubStat>,
    rankTitle: String,
    rankSubtitle: String,
    chartTitle: String,
    /** Levels 0..1, newest last; capped at 24 bars. */
    bars: List<Float>,
    chartLabel: String,
    modifier: Modifier = Modifier,
    rankIcon: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(56.dp)
                .alpha(0.25f)
        )
    }
) {
    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            // Header — title + timeframe chip.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                CloudChip(
                    text = timeFrame,
                    accentColor = MaterialTheme.ledger.lampDeep
                )
            }

            // Hero metric — small label, huge value, delta line.
            Column {
                Text(
                    text = heroLabel,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.ledger.deskInk
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = heroValue,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1.5).sp,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = heroDelta,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = when (heroDeltaTone) {
                            StatsDeltaTone.UP -> MaterialTheme.ledger.lampGlow
                            StatsDeltaTone.DOWN -> MaterialTheme.ledger.emberRed
                            StatsDeltaTone.NEUTRAL -> MaterialTheme.ledger.deskInk
                        }
                    )
                )
            }

            // Sub-stats — muted 2-up grid.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                subStats.take(2).forEach { stat ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.ledger.deskInk.copy(alpha = 0.08f))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stat.value,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.ledger.deskPaper
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stat.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.ledger.deskInk
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stat.subLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.deskInk
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Rank banner — highlighted place + category + decorative icon.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rankTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rankSubtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                rankIcon()
            }

            // Availability chart — staggered gradient bars + caption.
            Column {
                Text(
                    text = chartTitle,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                StaggeredBarChart(
                    levels = bars.takeLast(24),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = chartLabel
                        }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = chartLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.ledger.deskInk
                    )
                )
            }
        }
    }
}

/**
 * Bar chart with per-bar grow-in animation, staggered 50ms apart. The bar
 * color sweeps [Color] `lampDeep → lampAmber → lampGlow` across the index —
 * the ledger-palette answer to the source's blue→red gradient stops.
 */
@Composable
private fun StaggeredBarChart(
    levels: List<Float>,
    modifier: Modifier = Modifier
) {
    val lampDeep = MaterialTheme.ledger.lampDeep
    val lampGlow = MaterialTheme.ledger.lampGlow
    val track = MaterialTheme.ledger.deskInk.copy(alpha = 0.12f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (levels.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(track)
            )
            return@Row
        }
        levels.forEachIndexed { index, rawLevel ->
            val level = rawLevel.coerceIn(0f, 1f)
            val animated = remember { Animatable(0f) }
            LaunchedEffect(level) {
                // Stagger only the entry reveal; later updates glide directly.
                if (animated.value == 0f && level > 0f) delay(index * 50L)
                animated.animateTo(level, tween(durationMillis = 500))
            }
            // Two-stop sweep across the index (deep → glow), transparent track
            // behind live bars so empty slots still read as slots.
            val barColor: Color = lerp(
                lampDeep,
                lampGlow,
                if (levels.size > 1) index.toFloat() / (levels.size - 1) else 0.5f
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (level > 0f) Color.Transparent else track),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animated.value.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor)
                )
            }
        }
    }
}

@Preview(name = "Developer stats card", showBackground = true)
@Composable
private fun DeveloperStatsCardPreview() {
    AndroLLMTheme {
        DeveloperStatsCard(
            title = "Session Stats",
            timeFrame = "This session",
            heroLabel = "Throughput",
            heroValue = "18.4 tok/s",
            heroDelta = "+2.1 tok/s vs avg",
            heroDeltaTone = StatsDeltaTone.UP,
            subStats = listOf(
                StatsCardSubStat("48.2k", "tokens", "6 generations"),
                StatsCardSubStat("812", "MB RAM", "of 11,192 MB")
            ),
            rankTitle = "VULKAN",
            rankSubtitle = "active backend · 24/32 GPU layers",
            chartTitle = "Session load",
            bars = listOf(
                1f, 1f, 0.9f, 0.85f, 0.7f, 0.75f, 0.6f, 0.62f, 0.5f, 0.45f,
                0.4f, 0.38f, 0.3f, 0.28f, 0.2f, 0.18f, 0.15f, 0.1f
            ),
            chartLabel = "18 samples this session"
        )
    }
}
