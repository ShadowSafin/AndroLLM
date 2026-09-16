package io.androllm.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Developer HUD debug overlay displaying real-time metrics.
 */
@Composable
fun DebugOverlay(
    tokensPerSecond: Float,
    promptTokens: Long,
    generatedTokens: Long,
    activeBackend: String,
    memoryUsageMb: Long,
    threadCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(8.dp),
        color = Color(0xE6000000), // Neutral black HUD background
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "⚡ DEV DEBUG HUD",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFFFFF)
            )
            Text(
                text = "Speed: ${"%.1f".format(tokensPerSecond)} tok/s",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFA3A3A3)
            )
            Text(
                text = "Tokens: $promptTokens prompt / $generatedTokens gen",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFA3A3A3)
            )
            Text(
                text = "Backend: $activeBackend | Threads: $threadCount",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFEDEDED)
            )
            Text(
                text = "RAM: $memoryUsageMb MB",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFA3A3A3)
            )
        }
    }
}
