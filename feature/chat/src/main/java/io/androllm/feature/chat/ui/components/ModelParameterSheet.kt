package io.androllm.feature.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskNight
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import kotlin.math.roundToInt
import io.androllm.core.ui.theme.ledger

/**
 * The lamp's tuner — Temperature, Top-P, Repeat Penalty, and system personas.
 * One amber hand; the rest stays quiet walnut and ink.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelParameterSheet(
    onDismissRequest: () -> Unit,
    initialTemperature: Float = 0.7f,
    initialTopP: Float = 0.9f,
    initialMaxTokens: Int = 1024,
    onApplyParameters: (temp: Float, topP: Float, maxTokens: Int, systemPrompt: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()
    var temperature by remember { mutableFloatStateOf(initialTemperature) }
    var topP by remember { mutableFloatStateOf(initialTopP) }
    var maxTokens by remember { mutableFloatStateOf(initialMaxTokens.coerceIn(256, 8192).toFloat()) }
    var selectedPreset by remember { mutableStateOf("Default") }

    val systemPresets = listOf(
        "Default" to "You are a helpful, harmless, and honest AI assistant.",
        "Expert Coder" to "You are a senior principal software engineer. Provide clean, production-grade code with minimal fluff.",
        "Socratic Tutor" to "You are a Socratic educator. Guide the user through questions to help them discover solutions independently.",
        "Creative Writer" to "You are an imaginative author. Use rich metaphors and vivid descriptions."
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.ledger.deskNight,
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.ledger.lampDeep,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Engine Parameter Tuning",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.ledger.lampGlow.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "LIVE TUNER",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.ledger.lampDeep
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Temperature Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Temperature (Creativity)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = String.format("%.2f", temperature),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampGlow
                        )
                    )
                }

                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.1f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.ledger.lampGlow,
                        activeTrackColor = MaterialTheme.ledger.lampAmber,
                        inactiveTrackColor = MaterialTheme.ledger.deskHairline
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Top-P Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Top-P (Nucleus Sampling)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = String.format("%.2f", topP),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampAmber
                        )
                    )
                }

                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.ledger.lampAmber,
                        activeTrackColor = MaterialTheme.ledger.lampAmber,
                        inactiveTrackColor = MaterialTheme.ledger.deskHairline
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Max Output Length Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Max Output Length (tokens)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = "%.0f".format(maxTokens),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampGlow
                        )
                    )
                }

                Slider(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    // 8192 is far above any model's context window, so locally
                    // the answer effectively runs until the model finishes
                    // (the native engine clamps to the context). Cloud
                    // providers get the same 8192 ceiling.
                    valueRange = 256f..8192f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.ledger.lampGlow,
                        activeTrackColor = MaterialTheme.ledger.lampAmber,
                        inactiveTrackColor = MaterialTheme.ledger.deskHairline
                    )
                )
                Text(
                    text = "Answers run until the model finishes (bounded by the context window). Lower this to force shorter replies.",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.ledger.deskInkFaint
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // System Persona Presets
            Text(
                text = "System Persona",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(systemPresets) { (name, prompt) ->
                    val isSelected = selectedPreset == name
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskHairline.copy(alpha = 0.5f))
                            .clickable { selectedPreset = name }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = name,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInkFaint
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Apply Button
            CloudCapsuleButton(
                text = "Apply Engine Parameters",
                onClick = {
                    val prompt = systemPresets.firstOrNull { it.first == selectedPreset }?.second.orEmpty()
                    onApplyParameters(temperature, topP, maxTokens.roundToInt(), prompt)
                    onDismissRequest()
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
