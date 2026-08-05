package io.androllm.feature.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.LampDot
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskSlipShape
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.LampAmber

/**
 * The blank page — the desk at the start of a letter. A serif prompt and the
 * evening's suggested lines set as small-caps ruled chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewChatEmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = listOf(
        "Explain quantum computing simply",
        "Write a Kotlin function for binary search",
        "Summarize the history of AI",
        "Create a weekly workout routine"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LampDot(size = 12.dp, lit = true)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Ready when you are.",
            style = MaterialTheme.typography.headlineSmall.copy(
                color = DeskPaper
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Everything stays on your device — nothing is uploaded. Write below, or take one of these lines.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = DeskInk
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        FlowRow(
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            suggestions.forEach { suggestion ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(DeskWalnutRaised)
                        .border(1.dp, DeskHairline, RoundedCornerShape(18.dp))
                        .clickable { onSuggestionClick(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = suggestion.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 0.6.sp,
                            color = DeskInk
                        )
                    )
                }
            }
        }
    }
}

/**
 * The lamp is dark — no model loaded. A walnut slip on the ember edge, with
 * one amber way forward.
 */
@Composable
fun NoModelLoadedCard(
    onNavigateToModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(DeskSlipShape)
            .background(DeskWalnutRaised)
            .border(1.dp, io.androllm.core.ui.theme.EmberRed.copy(alpha = 0.5f), DeskSlipShape)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LampDot(size = 10.dp, lit = false)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "No Model Loaded",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = DeskPaper
                    )
                )
                Text(
                    text = "Load a model to start chatting.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = DeskInk
                    )
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            CloudCapsuleButton(
                text = "Models",
                onClick = onNavigateToModels
            )
        }
    }
}
