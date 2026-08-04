package io.androllm.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudGlassBorderHighlight
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.RevolutCyberCyan
import io.androllm.core.ui.theme.RevolutGoldTier
import io.androllm.core.ui.theme.RevolutNeonEmerald
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach
import io.androllm.core.ui.theme.SunsetGlowAmber

/**
 * Revolut-Inspired Hardware & On-Device AI Gauge Card.
 * Displays real-time RAM allocation, tokens/sec speed, and Vulkan GPU acceleration metrics.
 */
@Composable
fun RevolutResourceGaugeCard(
    ramUsedGb: Float,
    ramTotalGb: Float,
    tokensPerSecond: Float,
    vulkanEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(SunsetCloudOrange, SunsetGlowAmber))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = CloudWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "On-Device Engine Health",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = CloudWhite
                            )
                        )
                        Text(
                            text = if (vulkanEnabled) "Vulkan GPU Acceleration Active" else "CPU Multi-Thread Mode",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (vulkanEnabled) RevolutNeonEmerald else MoonSilver.copy(alpha = 0.6f)
                            )
                        )
                    }
                }

                RevolutHardwareBadge(isVulkan = vulkanEnabled)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Gauge Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Radial RAM Meter
                val ramPct = (ramUsedGb / ramTotalGb.coerceAtLeast(1f)).coerceIn(0f, 1f)
                CircularGaugeItem(
                    label = "RAM Allocated",
                    valueText = "${String.format("%.1f", ramUsedGb)} / ${String.format("%.0f", ramTotalGb)} GB",
                    progress = ramPct,
                    color = SunsetCloudPeach
                )

                // Radial Tokens/sec Meter
                val speedPct = (tokensPerSecond / 40f).coerceIn(0f, 1f)
                CircularGaugeItem(
                    label = "Inference Speed",
                    valueText = "${String.format("%.1f", tokensPerSecond)} t/s",
                    progress = speedPct,
                    color = RevolutNeonEmerald
                )
            }
        }
    }
}

@Composable
private fun CircularGaugeItem(
    label: String,
    valueText: String,
    progress: Float,
    color: Color
) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        animatedProgress.animateTo(progress, animationSpec = tween(durationMillis = 1200))
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(76.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 8.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                // Background Track Arc
                drawArc(
                    color = CloudGlassBorder,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active Progress Arc
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(color.copy(alpha = 0.5f), color, SunsetGlowAmber)
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f * animatedProgress.value,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    color = CloudWhite
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = CloudWhite
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = MoonSilver.copy(alpha = 0.6f)
            )
        )
    }
}

/**
 * Revolut Metal / Gold Style Hardware Tier Badge.
 */
@Composable
fun RevolutHardwareBadge(isVulkan: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isVulkan) {
                    Brush.horizontalGradient(listOf(RevolutGoldTier.copy(alpha = 0.25f), SunsetGlowAmber.copy(alpha = 0.15f)))
                } else {
                    Brush.horizontalGradient(listOf(CloudGlassBorder, Color.Transparent))
                }
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    if (isVulkan) listOf(RevolutGoldTier, SunsetGlowAmber) else listOf(CloudGlassBorder, Color.Transparent)
                ),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = null,
                tint = if (isVulkan) RevolutGoldTier else MoonSilver.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isVulkan) "VULKAN ULTRA" else "NEON CPU",
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp,
                color = if (isVulkan) RevolutGoldTier else MoonSilver
            )
        }
    }
}

/**
 * Revolut-Inspired Performance Waveform Graph.
 */
@Composable
fun RevolutPerformanceChartCard(
    dataPoints: List<Float>,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Generation Latency & Throughput",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CloudWhite
                    )
                )
                Text(
                    text = "Live Stream",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = RevolutNeonEmerald,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                if (dataPoints.isEmpty()) return@Canvas
                val width = size.width
                val height = size.height
                val maxVal = (dataPoints.maxOrNull() ?: 1f).coerceAtLeast(1f)
                val stepX = width / (dataPoints.size - 1).coerceAtLeast(1)

                val path = Path()
                val fillPath = Path()

                dataPoints.forEachIndexed { i, pt ->
                    val x = i * stepX
                    val y = height - (pt / maxVal * (height * 0.75f)) - (height * 0.1f)
                    if (i == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, height)
                        fillPath.lineTo(x, y)
                    } else {
                        val prevX = (i - 1) * stepX
                        val prevPt = dataPoints[i - 1]
                        val prevY = height - (prevPt / maxVal * (height * 0.75f)) - (height * 0.1f)
                        val cx = (prevX + x) / 2f
                        path.cubicTo(cx, prevY, cx, y, x, y)
                        fillPath.cubicTo(cx, prevY, cx, y, x, y)
                    }
                    if (i == dataPoints.size - 1) {
                        fillPath.lineTo(x, height)
                        fillPath.close()
                    }
                }

                // Draw Gradient Fill Under Graph
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            RevolutCyberCyan.copy(alpha = 0.35f),
                            SunsetCloudPeach.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )

                // Draw Smooth Curve Line
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        colors = listOf(SunsetCloudPeach, RevolutCyberCyan, RevolutNeonEmerald)
                    ),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
