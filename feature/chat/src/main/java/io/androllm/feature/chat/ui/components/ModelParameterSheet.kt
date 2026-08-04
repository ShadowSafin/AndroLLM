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
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.DarkAtmosphere
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.RevolutCyberCyan
import io.androllm.core.ui.theme.RevolutNeonEmerald
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach

/**
 * Revolut-Inspired Model Parameter Tuner Bottom Sheet.
 * Allows interactive fine-tuning of Temperature, Top-P, Repeat Penalty, and System Presets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelParameterSheet(
    onDismissRequest: () -> Unit,
    onApplyParameters: (temp: Float, topP: Float, systemPrompt: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState()
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var topP by remember { mutableFloatStateOf(0.9f) }
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
        containerColor = DarkAtmosphere,
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
                        tint = SunsetCloudPeach,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Engine Parameter Tuning",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = CloudWhite
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(RevolutNeonEmerald.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "LIVE TUNER",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        color = RevolutNeonEmerald
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
                            color = CloudWhite
                        )
                    )
                    Text(
                        text = String.format("%.2f", temperature),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = SunsetCloudPeach
                        )
                    )
                }

                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.1f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = SunsetCloudPeach,
                        activeTrackColor = SunsetCloudOrange,
                        inactiveTrackColor = CloudGlassBorder
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
                            color = CloudWhite
                        )
                    )
                    Text(
                        text = String.format("%.2f", topP),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = RevolutCyberCyan
                        )
                    )
                }

                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = RevolutCyberCyan,
                        activeTrackColor = RevolutCyberCyan,
                        inactiveTrackColor = CloudGlassBorder
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // System Persona Presets
            Text(
                text = "System Persona",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = CloudWhite
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(systemPresets) { (name, prompt) ->
                    val isSelected = selectedPreset == name
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) SunsetCloudOrange else Color(0x33E2E8F0))
                            .clickable { selectedPreset = name }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = name,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) CloudWhite else MoonSilver
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
                    onApplyParameters(temperature, topP, prompt)
                    onDismissRequest()
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
