package io.androllm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.voice.model.VoiceSettings
import io.androllm.core.ui.theme.ledger

/**
 * "Text Normalization" — per-stage toggles for the LLM→TTS normalization
 * pipeline (numbers, dates, currency, units, math, emoji, URLs/emails,
 * phones, abbreviations) plus the debug mode that traces each stage.
 */
@Composable
fun TextNormalizationSection(
    settings: VoiceSettings,
    onUpdate: (VoiceSettings) -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            ToggleRowTn(
                icon = Icons.Filled.Translate,
                title = "Text Normalization",
                subtitle = if (settings.tnEnabled)
                    "LLM output → speech-ready text (offline, < 20 ms)"
                else "Disabled — the TTS engine reads raw model output",
                checked = settings.tnEnabled,
                onCheckedChange = { onUpdate(settings.copy(tnEnabled = it)) }
            )

            HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.25f))

            if (settings.tnEnabled) {
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Numbers",
                    subtitle = "123 → one hundred twenty-three · 3.14 · 94% · 21st · v2.4.1",
                    checked = settings.tnNumbers,
                    onCheckedChange = { onUpdate(settings.copy(tnNumbers = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Dates & Times",
                    subtitle = "08/09/2026 → August ninth twenty twenty six · 14:30",
                    checked = settings.tnDates,
                    onCheckedChange = { onUpdate(settings.copy(tnDates = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Currencies",
                    subtitle = "\$20 → twenty dollars · €19.99 → nineteen euros…",
                    checked = settings.tnCurrency,
                    onCheckedChange = { onUpdate(settings.copy(tnCurrency = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Units",
                    subtitle = "1200 MHz → megahertz · 15 km → kilometers · 32°C",
                    checked = settings.tnUnits,
                    onCheckedChange = { onUpdate(settings.copy(tnUnits = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Math expressions",
                    subtitle = "2+2 · 10×5 · 16:9 · ≈ ≥ ≤ ÷",
                    checked = settings.tnMath,
                    onCheckedChange = { onUpdate(settings.copy(tnMath = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Emoji & symbols",
                    subtitle = "😊 → smiling face · # @ & ° ™ ®",
                    checked = settings.tnEmoji,
                    onCheckedChange = { onUpdate(settings.copy(tnEmoji = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "URLs & emails",
                    subtitle = "user@example.com → user at example dot com",
                    checked = settings.tnUrlsEmails,
                    onCheckedChange = { onUpdate(settings.copy(tnUrlsEmails = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Phone numbers",
                    subtitle = "+91 9876543210 → digit by digit",
                    checked = settings.tnPhones,
                    onCheckedChange = { onUpdate(settings.copy(tnPhones = it)) }
                )
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Abbreviations",
                    subtitle = "CPU → C P U · GHz → gigahertz · LLM → L L M",
                    checked = settings.tnAbbreviations,
                    onCheckedChange = { onUpdate(settings.copy(tnAbbreviations = it)) }
                )
                HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.15f))
                ToggleRowTn(
                    icon = Icons.Filled.FormatQuote,
                    title = "Debug mode",
                    subtitle = "Trace every stage to logcat (TN [stage])",
                    checked = settings.tnDebug,
                    onCheckedChange = { onUpdate(settings.copy(tnDebug = it)) }
                )
            } else {
                Text(
                    text = "Enable Text Normalization to customize stages.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ToggleRowTn(
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskInk)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.ledger.lampAmber)
        )
    }
}