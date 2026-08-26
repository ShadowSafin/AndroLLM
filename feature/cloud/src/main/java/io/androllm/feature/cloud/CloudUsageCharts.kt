package io.androllm.feature.cloud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.theme.ledger

/**
 * Canvas-drawn trend charts for the cloud usage dashboard.
 *
 * Deliberately dependency-free (no chart library): the dashboard needs only
 * sparklines, line trends and bar charts, and drawing them keeps the visual
 * language identical to the rest of AndroLLM's ledger theme.
 */

/** A smooth line chart with a soft fill under the curve. */
@Composable
fun UsageLineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.ledger.lampAmber,
    fillColor: Color = MaterialTheme.ledger.lampHalo,
    gridColor: Color = MaterialTheme.ledger.deskHairline
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return@Canvas
        val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f

        // Baseline gridlines (25/50/75%).
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            val y = h * (1f - fraction)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
            )
        }

        if (values.isEmpty()) return@Canvas
        if (values.size == 1) {
            drawCircle(color = lineColor, radius = 4f, center = Offset(w / 2, h - (values[0] / max) * (h - 8f) - 4f))
            return@Canvas
        }

        val stepX = w / (values.size - 1)
        fun pointAt(index: Int): Offset {
            val normalized = (values[index].coerceAtLeast(0f) / max) * (h - 8f)
            return Offset(index * stepX, h - normalized - 4f)
        }

        // Fill under the curve.
        val fillPath = Path().apply {
            moveTo(0f, h)
            for (i in values.indices) lineTo(pointAt(i).x, pointAt(i).y)
            lineTo(w, h)
            close()
        }
        drawPath(fillPath, color = fillColor)

        // The curve itself.
        val linePath = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // End dot.
        val last = pointAt(values.size - 1)
        drawCircle(color = lineColor, radius = 5f, center = last)
    }
}

/** A vertical bar chart with optional value labels. */
@Composable
fun UsageBarChart(
    values: List<Float>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.ledger.lampAmber,
    dimBarColor: Color = MaterialTheme.ledger.deskHairline,
    highlightLast: Boolean = true
) {
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val w = size.width
            val h = size.height
            if (w <= 0 || h <= 0 || values.isEmpty()) return@Canvas
            val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
            val slot = w / values.size
            val barWidth = (slot * 0.6f).coerceAtLeast(4f)
            for (i in values.indices) {
                val barHeight = (values[i].coerceAtLeast(0f) / max) * (h - 6f)
                val left = i * slot + (slot - barWidth) / 2
                val isLast = i == values.size - 1
                drawRoundRect(
                    color = if (highlightLast && isLast) barColor else barColor.copy(alpha = if (values[i] <= 0f) 0.15f else 0.55f),
                    topLeft = Offset(left, h - barHeight),
                    size = Size(barWidth, barHeight.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
        }
        if (labels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.deskInkFaint
                    )
                }
            }
        }
    }
}

/** Compact sparkline for metric cards. */
@Composable
fun UsageSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.ledger.lampDeep
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0 || values.size < 2) return@Canvas
        val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val stepX = w / (values.size - 1)
        val path = Path().apply {
            for (i in values.indices) {
                val x = i * stepX
                val y = h - (values[i].coerceAtLeast(0f) / max) * (h - 4f) - 2f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

/** A thin horizontal progress-style bar (success rate, cache hit rate...). */
@Composable
fun UsageRatioBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.ledger.revolutNeonEmerald,
    trackColor: Color = MaterialTheme.ledger.deskHairlineSoft
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0 || h <= 0) return@Canvas
        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(h / 2, h / 2)
        )
        val filled = (fraction.coerceIn(0f, 1f)) * w
        if (filled > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(filled, h),
                cornerRadius = CornerRadius(h / 2, h / 2)
            )
        }
    }
}
