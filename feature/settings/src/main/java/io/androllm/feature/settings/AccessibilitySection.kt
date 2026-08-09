package io.androllm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androllm.core.accessibility.settings.AccessibilitySettings
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow

/**
 * "UI Automation" — the accessibility automation engine. Native APIs, MCP
 * tools and app intents are always preferred; this service powers the
 * fallback that lets the assistant actually operate third-party apps.
 */
@Composable
fun AccessibilitySection(
    settings: AccessibilitySettings,
    serviceEnabled: Boolean,
    connected: Boolean,
    onUpdate: (AccessibilitySettings) -> Unit,
    onOpenSettings: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Status + enable entry point
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = LampGlow,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Accessibility service", style = MaterialTheme.typography.bodyMedium, color = DeskPaper)
                    Text(
                        when {
                            serviceEnabled && connected -> "Active — controlling apps when you ask"
                            serviceEnabled -> "Enabled in settings"
                            else -> "Off — tap to enable in system settings"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (serviceEnabled) DeskInk else LampAmber
                    )
                }
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Open accessibility settings",
                    tint = DeskInkFaint,
                    modifier = Modifier.size(20.dp)
                )
            }

            HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.15f))

            AccToggleRow(
                icon = Icons.Filled.TouchApp,
                title = "Scroll into view",
                subtitle = "Scroll lists until the target element is visible before tapping",
                checked = settings.autoScrollIntoView,
                onCheckedChange = { onUpdate(settings.copy(autoScrollIntoView = it)) }
            )

            AccToggleRow(
                icon = Icons.Filled.TouchApp,
                title = "LLM planning",
                subtitle = "Use the local model to pick each step (falls back to rules)",
                checked = settings.llmPlanning,
                onCheckedChange = { onUpdate(settings.copy(llmPlanning = it)) }
            )

            AccToggleRow(
                icon = Icons.Filled.TouchApp,
                title = "Confirm high-risk steps",
                subtitle = "Ask before anything that sends, pays, books, deletes or installs",
                checked = settings.confirmHighRisk,
                onCheckedChange = { onUpdate(settings.copy(confirmHighRisk = it)) }
            )

            AccToggleRow(
                icon = Icons.Filled.BugReport,
                title = "Developer mode",
                subtitle = "Record execution trees, node dumps and gesture logs",
                checked = settings.developerMode,
                onCheckedChange = { onUpdate(settings.copy(developerMode = it)) }
            )
        }
    }
}

@Composable
private fun AccToggleRow(
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
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LampGlow, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = DeskPaper)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = DeskInk,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = LampAmber)
        )
    }
}
