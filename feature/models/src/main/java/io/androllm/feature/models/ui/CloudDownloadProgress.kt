package io.androllm.feature.models.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.ui.theme.ledger

/**
 * Parchment — a lit progress ring for the model shelf. One terracotta sweep
 * as the weight settles into the drawer.
 */
@Composable
fun CloudDownloadProgress(
    progressPercent: Int,
    speedBytesPerSec: Float,
    etaSeconds: Long,
    isCompleted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (progressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "progressRing"
    )

    Box(
        modifier = modifier.size(100.dp),
        contentAlignment = Alignment.Center
    ) {
        val ledger = MaterialTheme.ledger
        val trackColor = ledger.lampDeep.copy(alpha = 0.3f)
        val progressColors = listOf(ledger.lampAmber, ledger.lampGlow, ledger.lampAmber, ledger.lampAmber)
        val completedColor = ledger.lampGlow
        val textColor = ledger.deskPaper
        val speedColor = ledger.deskInk

        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 8.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            val arcSize = Size(diameter, diameter)

            // Track Arc — the quiet cream ring
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )

            // Progress Arc — the terracotta sweep
            drawArc(
                brush = Brush.sweepGradient(progressColors),
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Completed",
                    tint = completedColor,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Text(
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                )
                if (speedBytesPerSec > 0f) {
                    Text(
                        text = speedBytesPerSec.formatSpeed(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            color = speedColor
                        )
                    )
                }
            }
        }
    }
}

/**
 * Formats bytes/sec into a human-readable speed string using binary units.
 *
 * Conversion rules:
 *   < 1024 B     -> "512 B/s"
 *   < 1024 KB    -> "845 KB/s"
 *   < 1024 MB    -> "9.82 MB/s"
 *   >= 1024 MB   -> "1.50 GB/s"
 */
private fun Float.formatSpeed(): String {
    return when {
        this < 1024f -> String.format(java.util.Locale.getDefault(), "%.0f B/s", this)
        this < 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.0f KB/s", this / 1024f)
        this < 1024f * 1024f * 1024f -> String.format(java.util.Locale.getDefault(), "%.2f MB/s", this / (1024f * 1024f))
        else -> String.format(java.util.Locale.getDefault(), "%.2f GB/s", this / (1024f * 1024f * 1024f))
    }
}