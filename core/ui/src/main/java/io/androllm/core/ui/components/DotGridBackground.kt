package io.androllm.core.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.sqrt

private val DotOpacities = listOf(0.3f, 0.3f, 0.3f, 0.5f, 0.5f, 0.5f, 0.8f, 0.8f, 0.8f, 1.0f)

/** Deterministic 2D hash → [0, 1); integer math wraps like the shader's fract. */
private fun dotHash(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 1442695041
    h = (h xor (h shr 13)) * 1274126177
    h = h xor (h shr 16)
    return ((h ushr 8) and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
}

/**
 * Dot-grid background — a native port of the sign-in page's WebGL shader: a
 * centered grid of dots with bucketed opacities, a 5-second twinkle cycle and
 * a center-out intro reveal.
 *
 * Redrawn at ~10fps; dots are static between twinkles, so it stays cheap
 * enough to sit behind every screen. When [animate] is false (reduce-motion)
 * a fully-revealed static frame is drawn once.
 *
 * @param dotColor dot ink — white on dark grounds, ink on light ones.
 * @param alphaScale overall multiplier over the baked opacity buckets.
 */
@Composable
fun DotGridBackground(
    modifier: Modifier = Modifier,
    dotColor: Color = Color.White,
    alphaScale: Float = 1f,
    animate: Boolean = true
) {
    var timeSec by remember(animate) { mutableFloatStateOf(if (animate) 0f else 5f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = SystemClock.uptimeMillis()
            while (true) {
                timeSec = (SystemClock.uptimeMillis() - start) / 1000f
                delay(100L)
            }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val cell = 8.dp.toPx()
        val dotRadius = cell * (6f / 20f) / 2f
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        // Center the grid, mirroring the shader's abs(floor((mod-total)*0.5)) inset.
        val offsetX = kotlin.math.abs(floor(((size.width % cell) - dotRadius * 2f) * 0.5f))
        val offsetY = kotlin.math.abs(floor(((size.height % cell) - dotRadius * 2f) * 0.5f))
        val centerX = cols / 2f
        val centerY = rows / 2f

        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val showOffset = dotHash(cx, cy, 7)
                val bucket = floor(timeSec / 5f + showOffset + 5f).toInt()
                val rand = dotHash(cx, cy, bucket)
                var alpha = DotOpacities[(rand * 10f).toInt().coerceIn(0, 9)] * alphaScale

                val dist = sqrt(
                    (cx - centerX) * (cx - centerX) + (cy - centerY) * (cy - centerY)
                )
                val timingOffset = dist * 0.01f + dotHash(cx, cy, 13) * 0.15f
                if (timeSec * 3f < timingOffset) alpha = 0f
                if (alpha <= 0f) continue

                drawCircle(
                    color = dotColor,
                    radius = dotRadius,
                    center = Offset(offsetX + cx * cell, offsetY + cy * cell),
                    alpha = alpha.coerceIn(0f, 1f)
                )
            }
        }
    }
}
