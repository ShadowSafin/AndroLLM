package io.androllm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.core.voice.stt.WhisperModel

/**
 * "Speech Recognition" — whisper.cpp engine selection, model download/delete,
 * language, advanced decoding, max recording time and streaming mode.
 */
@Composable
fun SpeechRecognitionSection(
    settings: VoiceSettings,
    models: List<WhisperModel>,
    installedIds: Set<String>,
    download: Pair<String, Float>?,
    message: String?,
    storageBytes: Long,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onUpdate: (VoiceSettings) -> Unit
) {
    val activeModel = models.firstOrNull { it.id == settings.whisperModel }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
        SectionRow(
            icon = Icons.Filled.RecordVoiceOver,
            title = "Speech Recognition",
            subtitle = "Engine: whisper.cpp (offline) · ${activeModel?.displayName ?: "no model installed"}"
        )

        HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.25f))

        // ── Model download / select ──
        models.forEachIndexed { index, model ->
            val installed = model.id in installedIds
            val active = settings.whisperModel == model.id
            val downloading = download?.first == model.id
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable(enabled = installed, onClick = { onSelectModel(model.id) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (active) Icons.Filled.CheckCircle else Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = if (active) LampAmber else DeskInkFaint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = DeskPaper,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = model.sizeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = DeskInk
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (active) {
                        Text(
                            text = if (installed) "In use" else "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = LampAmber
                        )
                    }
                    if (!installed) {
                        TextButton(
                            enabled = !downloading,
                            onClick = { onDownloadModel(model.id) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = if (downloading) "Downloading" else "Download",
                                style = MaterialTheme.typography.labelSmall,
                                color = LampAmber,
                                maxLines = 1
                            )
                        }
                    } else if (!active) {
                        TextButton(
                            onClick = { onDeleteModel(model.id) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = "Delete",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF8A80),
                                maxLines = 1
                            )
                        }
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { download.second },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                }
                if (index < models.lastIndex) {
                    HorizontalDivider(
                        color = DeskInkFaint.copy(alpha = 0.15f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = DeskInk,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.25f))

        // ── Language ──
        ToggleRowStt(
            icon = Icons.Filled.Translate,
            title = "Language",
            subtitle = if (settings.sttLanguage == "auto") "Auto-detect" else settings.sttLanguage,
            checked = settings.sttLanguage == "auto",
            onCheckedChange = { auto ->
                onUpdate(settings.copy(sttLanguage = if (auto) "auto" else "en"))
            }
        )
        ToggleRowStt(
            icon = Icons.Filled.Translate,
            title = "Translate to English",
            subtitle = "Transcribe any language into English",
            checked = settings.sttTranslate,
            onCheckedChange = { onUpdate(settings.copy(sttTranslate = it)) }
        )
        ToggleRowStt(
            icon = Icons.Filled.Speed,
            title = "Streaming mode",
            subtitle = "Live partial transcripts while you speak",
            checked = settings.sttStreaming,
            onCheckedChange = { onUpdate(settings.copy(sttStreaming = it)) }
        )

        HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.15f))

        SliderRow(
            label = { "CPU threads  ${if (settings.sttThreads < 0) "auto" else settings.sttThreads}" },
            value = if (settings.sttThreads < 0) VoiceSettings.MIN_STT_THREADS.toFloat()
            else settings.sttThreads.toFloat(),
            range = VoiceSettings.MIN_STT_THREADS.toFloat()..10f,
            onValue = { onUpdate(settings.copy(sttThreads = it.toInt())) }
        )
        SliderRow(
            label = { "Beam size  ${settings.sttBeamSize}" },
            value = settings.sttBeamSize.toFloat(),
            range = VoiceSettings.MIN_STT_BEAM.toFloat()..VoiceSettings.MAX_STT_BEAM.toFloat(),
            onValue = { onUpdate(settings.copy(sttBeamSize = it.toInt())) }
        )
        SliderRow(
            label = { "Temperature  ${String.format("%.1f", settings.sttTemperature)}" },
            value = settings.sttTemperature.coerceIn(VoiceSettings.MIN_STT_TEMPERATURE, VoiceSettings.MAX_STT_TEMPERATURE),
            range = VoiceSettings.MIN_STT_TEMPERATURE..VoiceSettings.MAX_STT_TEMPERATURE,
            onValue = { onUpdate(settings.copy(sttTemperature = it)) }
        )
        SliderRow(
            label = { "Max recording  ${settings.sttMaxSeconds}s" },
            value = settings.sttMaxSeconds.toFloat(),
            range = VoiceSettings.MIN_STT_MAX_SECONDS.toFloat()..VoiceSettings.MAX_STT_MAX_SECONDS.toFloat(),
            onValue = { onUpdate(settings.copy(sttMaxSeconds = it.toInt())) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Models stored: ${storageLabel(storageBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = DeskInk,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            TextButton(onClick = {
                onUpdate(
                    settings.copy(
                        whisperModel = "base.en",
                        sttLanguage = "auto",
                        sttTranslate = false,
                        sttThreads = -1,
                        sttBeamSize = 1,
                        sttTemperature = 0.0f,
                        sttMaxSeconds = 30,
                        sttStreaming = true
                    )
                )
            }) {
                Text("Reset defaults", style = MaterialTheme.typography.labelSmall)
            }
        }
        }
    }
}

// ── Small helpers (kept private) ──────────────────────────────────────────

private fun storageLabel(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format("%d MB", bytes / (1024 * 1024))
        bytes > 0 -> String.format("%d KB", bytes / 1024)
        else -> "0 MB"
    }

@Composable
private fun SliderRow(
    label: () -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label(), style = MaterialTheme.typography.labelSmall, color = DeskInk)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range
        )
    }
}

@Composable
private fun ToggleRowStt(
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LampGlow, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = DeskPaper)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DeskInk)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = LampAmber)
        )
    }
}

@Composable
private fun SectionRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LampAmber, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = DeskPaper)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DeskInk)
        }
    }
}