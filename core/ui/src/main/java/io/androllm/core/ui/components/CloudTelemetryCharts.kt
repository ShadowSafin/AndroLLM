package io.androllm.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver

/**
 * Smooth animated line chart with gradient fill — used for live tokens/sec
 * and RAM history. All data points are real session telemetry.
 */
@Composable
fun CloudLineChart(
    dataPoints: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 120.dp,
    fill: Boolean = true
) {
    val reveal = remember(dataPoints) { Animatable(0f) }
    LaunchedEffect(dataPoints) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(durationMillis = 700))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (dataPoints.isEmpty()) return@Canvas
        val width = size.width
        val chartHeight = size.height
        val maxVal = (dataPoints.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val minVal = (dataPoints.minOrNull() ?: 0f).coerceAtMost(0f)
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val stepX = width / (dataPoints.size - 1).coerceAtLeast(1)

        fun yFor(v: Float): Float =
            chartHeight - ((v - minVal) / range * (chartHeight * 0.8f)) - (chartHeight * 0.08f)

        // Grid baseline
        drawLine(
            color = CloudGlassBorder,
            start = Offset(0f, chartHeight * 0.92f),
            end = Offset(width, chartHeight * 0.92f),
            strokeWidth = 1.dp.toPx()
        )

        val path = Path()
        val fillPath = Path()
        dataPoints.forEachIndexed { i, pt ->
            val x = i * stepX * reveal.value
            val y = yFor(pt)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, chartHeight)
                fillPath.lineTo(x, y)
            } else {
                val prevX = (i - 1) * stepX * reveal.value
                val prevY = yFor(dataPoints[i - 1])
                val cx = (prevX + x) / 2f
                path.cubicTo(cx, prevY, cx, y, x, y)
                fillPath.cubicTo(cx, prevY, cx, y, x, y)
            }
            if (i == dataPoints.size - 1) {
                fillPath.lineTo(x, chartHeight)
                fillPath.close()
            }
        }

        if (fill) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.30f),
                        accent.copy(alpha = 0.04f),
                        Color.Transparent
                    )
                )
            )
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.55f))),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Rounded vertical bar chart — used for per-generation latency and throughput.
 */
@Composable
fun CloudBarChart(
    values: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 120.dp,
    maxBars: Int = 24
) {
    val reveal = remember(values) { Animatable(0f) }
    LaunchedEffect(values) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(durationMillis = 600))
    }

    val visible = values.takeLast(maxBars)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (visible.isEmpty()) return@Canvas
        val maxVal = (visible.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val slot = size.width / visible.size
        val barWidth = (slot * 0.6f).coerceAtLeast(2f)

        drawLine(
            color = CloudGlassBorder,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )

        visible.forEachIndexed { i, v ->
            val barHeight = (v / maxVal) * (size.height * 0.9f) * reveal.value
            val left = i * slot + (slot - barWidth) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(accent, accent.copy(alpha = 0.45f))
                ),
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}

/**
 * Horizontal usage bar (context window, storage, KV cache) with label + value.
 */
@Composable
fun CloudUsageBar(
    label: String,
    valueText: String,
    fraction: Float,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MoonSilver.copy(alpha = 0.85f)
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = CloudWhite
                )
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        val clamped = fraction.coerceIn(0f, 1f)
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            drawRoundRect(
                color = accent.copy(alpha = 0.15f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            if (clamped > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.6f))),
                    size = Size(size.width * clamped, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
    }
}
