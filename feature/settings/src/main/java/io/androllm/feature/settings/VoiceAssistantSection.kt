package io.androllm.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.feature.voice.VoicePhase
import io.androllm.feature.voice.VoiceUiState

/**
 * "Voice Assistant" — the always-available hands-free assistant: wake word,
 * sensitivity, battery modes, TTS, overlay permission and live status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistantSection(
    settings: VoiceSettings,
    liveState: VoiceUiState,
    overlayGranted: Boolean,
    onUpdate: (VoiceSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenOverlayPermission: () -> Unit
) {
    var showWakePhraseDialog by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    // RECORD_AUDIO (and POST_NOTIFICATIONS on 13+) are requested the first
    // time the assistant is enabled; the service can't start without the mic.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            onStart()
        } else {
            permissionDenied = true
        }
    }

    fun requestEnable() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Master switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (settings.enabled) onStop() else requestEnable()
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = if (settings.enabled) LampAmber else LampGlow,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice Assistant",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = DeskPaper
                        )
                    )
                    Text(
                        text = when {
                            liveState.active -> statusLabel(liveState.phase)
                            settings.enabled -> "Starting\u2026"
                            else -> "Always-on wake word \u2014 say \u201CHey Andro\u201D"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (liveState.active) LampDeep else DeskInk
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { if (it) requestEnable() else onStop() },
                    colors = SwitchDefaults.colors(checkedThumbColor = LampAmber)
                )
            }

            if (permissionDenied) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "The microphone permission is required for the assistant.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFF8A80)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (settings.enabled) {
                HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.25f))

                ToggleRow(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "Enable Wake Word",
                    subtitle = "Listen continuously for \"Hey Andro\"",
                    checked = settings.enableWakeWord,
                    onCheckedChange = { onUpdate(settings.copy(enableWakeWord = it)) }
                )

                SettingRow(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "Wake phrase",
                    value = settings.wakePhrases.joinToString(", ").ifBlank { "hey andro" },
                    onClick = { showWakePhraseDialog = true }
                )

                // Sensitivity
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Wake word sensitivity  ${(settings.sensitivity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.sensitivity,
                        onValueChange = { onUpdate(settings.copy(sensitivity = it)) },
                        valueRange = VoiceSettings.MIN_SENSITIVITY..VoiceSettings.MAX_SENSITIVITY,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Silence timeout
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Silence timeout  ${settings.silenceTimeoutMs / 1000f}s",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.silenceTimeoutMs.toFloat(),
                        onValueChange = { onUpdate(settings.copy(silenceTimeoutMs = it.toInt())) },
                        valueRange = VoiceSettings.MIN_SILENCE_TIMEOUT_MS.toFloat()..
                            VoiceSettings.MAX_SILENCE_TIMEOUT_MS.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Speaking speed
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Speaking speed  ${settings.speakingSpeed}x",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.speakingSpeed,
                        onValueChange = { onUpdate(settings.copy(speakingSpeed = it)) },
                        valueRange = VoiceSettings.MIN_SPEED..VoiceSettings.MAX_SPEED,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Pitch
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Voice pitch  ${settings.pitch}x",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.pitch,
                        onValueChange = { onUpdate(settings.copy(pitch = it)) },
                        valueRange = VoiceSettings.MIN_PITCH..VoiceSettings.MAX_PITCH,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Volume
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Voice volume  ${(settings.volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                    )
                    Slider(
                        value = settings.volume,
                        onValueChange = { onUpdate(settings.copy(volume = it)) },
                        valueRange = VoiceSettings.MIN_VOLUME..VoiceSettings.MAX_VOLUME,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ToggleRow(
                    icon = Icons.Filled.GraphicEq,
                    title = "Auto Language Detection",
                    subtitle = "Detect input language automatically",
                    checked = settings.autoLanguageDetection,
                    onCheckedChange = { onUpdate(settings.copy(autoLanguageDetection = it)) }
                )
                ToggleRow(
                    icon = Icons.Filled.GraphicEq,
                    title = "Battery saver",
                    subtitle = "Minimal CPU \u2014 lowest power listening",
                    checked = settings.batterySaver,
                    onCheckedChange = { onUpdate(settings.copy(batterySaver = it)) }
                )
                ToggleRow(
                    icon = Icons.Filled.Timer,
                    title = "Charging only",
                    subtitle = "Only listen while the phone is plugged in",
                    checked = settings.chargingOnly,
                    onCheckedChange = { onUpdate(settings.copy(chargingOnly = it)) }
                )
                ToggleRow(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "Continuous conversation",
                    subtitle = "Keep listening after answers (hands-free)",
                    checked = settings.continuousConversation,
                    onCheckedChange = { onUpdate(settings.copy(continuousConversation = it)) }
                )
                ToggleRow(
                    icon = Icons.Filled.RecordVoiceOver,
                    title = "Auto-read answers",
                    subtitle = "Speak responses aloud",
                    checked = settings.autoReadAnswers,
                    onCheckedChange = { onUpdate(settings.copy(autoReadAnswers = it)) }
                )
                ToggleRow(
                    icon = Icons.Filled.Speed,
                    title = "Low latency mode",
                    subtitle = "Skip memory retrieval for faster replies",
                    checked = settings.lowLatencyMode,
                    onCheckedChange = { onUpdate(settings.copy(lowLatencyMode = it)) }
                )
                ToggleRow(
                    icon = Icons.Filled.Mic,
                    title = "100% Offline Voice",
                    subtitle = "Wake word, ASR & TTS run completely on-device",
                    checked = settings.offlineOnly,
                    onCheckedChange = { onUpdate(settings.copy(offlineOnly = it)) }
                )

                HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.25f))

                // Overlay permission
                SettingRow(
                    icon = Icons.Filled.OpenInNew,
                    title = "Floating overlay",
                    value = if (overlayGranted) "Allowed" else "Not allowed",
                    onClick = onOpenOverlayPermission
                )

                // Real-Time Debug Overlay Card (Phase 8 & Phase 2 & Phase 3)
                Card(
                    colors = CardDefaults.cardColors(containerColor = DeskInkFaint.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Live Debug Overlay (Real-Time Metrics)",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = LampAmber
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Phase: ${liveState.phase} | Owner: ${liveState.micOwner}",
                            style = MaterialTheme.typography.bodySmall.copy(color = DeskPaper)
                        )
                        Text(
                            text = "ONNX Status: ${liveState.onnxStatus}",
                            style = MaterialTheme.typography.bodySmall.copy(color = DeskPaper)
                        )
                        Text(
                            text = "Audio RMS: ${String.format("%.4f", liveState.micRms)} | Max Amp: ${String.format("%.4f", liveState.maxAmplitude)}",
                            style = MaterialTheme.typography.bodySmall.copy(color = DeskPaper)
                        )
                        Text(
                            text = "KWS Confidence: ${String.format("%.2f", liveState.confidenceScore)} | Threshold: ${liveState.threshold}",
                            style = MaterialTheme.typography.bodySmall.copy(color = DeskPaper)
                        )
                        Text(
                            text = "Frames Received: ${liveState.framesReceived}",
                            style = MaterialTheme.typography.bodySmall.copy(color = DeskInk)
                        )
                    }
                }

                val error = liveState.error
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFF8A80)),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                CloudCapsuleButton(
                    text = if (liveState.active) "Stop assistant" else "Start assistant",
                    onClick = { if (liveState.active) onStop() else requestEnable() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (showWakePhraseDialog) {
        WakePhraseDialog(
            current = settings.wakePhrases.joinToString(", "),
            onDismiss = { showWakePhraseDialog = false },
            onSave = { phrases ->
                showWakePhraseDialog = false
                onUpdate(settings.copy(wakePhrases = phrases))
            }
        )
    }
}

@Composable
private fun statusLabel(phase: VoicePhase): String = when (phase) {
    VoicePhase.IDLE -> "Ready"
    VoicePhase.WAKE, VoicePhase.LISTENING -> "Listening for \u201CHey Andro\u201D\u2026"
    VoicePhase.LISTEN, VoicePhase.RECEIVING_AUDIO -> "Listening to speech\u2026"
    VoicePhase.RUNNING_INFERENCE -> "Processing wake word\u2026"
    VoicePhase.WAKE_DETECTED -> "Wake word detected!"
    VoicePhase.STARTING_STT -> "Starting speech recognition\u2026"
    VoicePhase.THINK, VoicePhase.GENERATING -> "Thinking\u2026"
    VoicePhase.SPEAK, VoicePhase.SPEAKING -> "Speaking\u2026"
    VoicePhase.DONE -> "Ready"
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LampDeep,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = DeskPaper
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = DeskInk),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = LampAmber)
        )
    }
}

@Composable
private fun WakePhraseDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var phrases by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wake phrases", fontWeight = FontWeight.Bold, color = DeskPaper) },
        text = {
            Column {
                Text(
                    text = "Comma-separated phrases (lowercase words work best, e.g. \u201Chey andro, okay andro\u201D).",
                    style = MaterialTheme.typography.bodySmall.copy(color = DeskInk)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = phrases,
                    onValueChange = { phrases = it },
                    label = { Text("Wake phrases") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        phrases.split(",")
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                    )
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = LampAmber)
            ) {
                Text("Save", color = DeskPaper)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = DeskInkFaint) }
        }
    )
}
