package io.androllm.feature.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineStats
import kotlin.math.roundToInt

/**
 * Expandable live telemetry panel, hidden by default.
 *
 * The compact pill above the composer shows only the essentials (context
 * meter + tok/s). Tapping it expands this panel with the full picture: token
 * counts, latency split (prompt / decode), backend, KV cache, batch and
 * thread configuration. Every value is real engine telemetry.
 */
@Composable
fun GenerationStatsPanel(
    stats: EngineStats?,
    contextLength: Int,
    usedTokens: Long,
    isGenerating: Boolean,
    cloudMode: Boolean,
    model: EngineModelInfo?,
    liveTokenCount: Long,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasData = contextLength > 0 || stats != null || isGenerating

    Column(modifier = modifier.fillMaxWidth()) {
        // ── The compact pill ──────────────────────────────────────────────────
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = DeskWalnutRaised.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, DeskHairline),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onToggleExpanded)
    ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (hasData) {
                            if (contextLength > 0) "Context $usedTokens / $contextLength" else "Engine active"
                        } else {
                            "Idle"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 1.1.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DeskInk
                        )
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isGenerating) {
                            Text(
                                text = String.format("%.1f tok/s", stats?.tokensPerSecond ?: 0f),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = LampDeep
                                )
                            )
                        } else if (stats != null && stats.generatedTokens > 0) {
                            Text(
                                text = String.format("%.1f tok/s", stats.tokensPerSecond),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = DeskInk
                                )
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse stats" else "Expand stats",
                            tint = DeskInkFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                if (contextLength > 0) {
                    Spacer(modifier = Modifier.height(7.dp))
                    LinearProgressIndicator(
                        progress = { (usedTokens.toFloat() / contextLength).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(LampAmber.copy(alpha = 0.12f), RoundedCornerShape(999.dp)),
                        color = if (usedTokens.toFloat() / contextLength > 0.85f) EmberRed else LampAmber,
                        trackColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                }
            }
        }

        // ── The expanded telemetry panel ──────────────────────────────────────
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DeskWalnutRaised.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, DeskHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    val gen = stats
                    val totalTokens = (gen?.promptTokens ?: 0L) +
                        if (isGenerating) liveTokenCount else (gen?.generatedTokens ?: 0L)

                    if (isGenerating) {
                        TelemetryRow("Generating", "$liveTokenCount tokens", LampDeep)
                    }
                    TelemetryRow(
                        "Tokens",
                        "${gen?.promptTokens ?: 0} prompt · $totalTokens total"
                    )
                    TelemetryRow("Latency", formatMillis(gen?.totalTimeMs))
                    TelemetryRow("First token", formatMillis(gen?.firstTokenMs))
                    TelemetryRow(
                        "Decode",
                        "${formatMillis(gen?.generationTimeMs)} (${formatPerSecond(gen?.tokensPerSecond)})"
                    )
                    TelemetryRow("Prompt time", formatMillis(gen?.promptTimeMs))
                    TelemetryRow("Stop reason", gen?.stopReason ?: "—")

                    val backendLabel = when {
                        cloudMode -> "Cloud (LiteLLM)"
                        model != null -> model.backend.name.replace('_', ' ').lowercase()
                            .replaceFirstChar { it.uppercase() }
                        else -> "Not loaded"
                    }
                    TelemetryRow("Backend", backendLabel)
                    if (model != null) {
                        TelemetryRow("Model", model.id, accent = LampDeep)
                        if (model.kvType.isNotBlank()) TelemetryRow("KV cache", model.kvType)
                        if (model.nBatch > 0) TelemetryRow("Batch", "${model.nBatch}/${model.nUbatch}")
                        if (model.nThreads > 0) TelemetryRow("Threads", "${model.nThreads}")
                        if (model.quantization.isNotBlank()) TelemetryRow("Quant", model.quantization)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, accent: androidx.compose.ui.graphics.Color = DeskInk) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 0.8.sp,
                color = DeskInkFaint
            )
        )
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = accent
            ),
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun formatMillis(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    return if (ms < 1000) "${ms} ms" else String.format("%.2f s", ms / 1000f)
}

private fun formatPerSecond(tps: Float?): String {
    if (tps == null || tps <= 0f) return "—"
    return String.format("%.1f tok/s", tps)
}
