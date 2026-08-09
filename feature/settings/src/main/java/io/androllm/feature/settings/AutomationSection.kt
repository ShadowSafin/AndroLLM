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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Rule
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.runtimePermissions
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.utils.PermissionUtils
import io.androllm.core.tools.settings.ConfirmationMode
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow

/**
 * "Automation" — per-tool permission management for the tool-calling
 * pipeline. The master switch flips the whole framework on; every registered
 * tool (including future plugins) appears below with its own toggle, grouped
 * by category. Confirmation strictness is also configured here.
 */
@Composable
fun AutomationSection(
    settings: AutomationSettings,
    tools: List<Tool>,
    onUpdate: (AutomationSettings) -> Unit,
    onRequestPermissions: (List<String>) -> Unit = {}
) {
    // Runtime permissions the tools need before they can actually run. The
    // confirmation card also requests them on approve; this block lets the
    // user pre-grant them (e.g. for voice mode, where there is no card tap).
    val context = LocalContext.current
    val missingDevicePermissions = permissionGroups
        .filter { (_, perms) -> perms.any { !PermissionUtils.hasPermission(context, it) } }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            AutoToggleRow(
                icon = Icons.Filled.Bolt,
                title = "Tool Calling",
                subtitle = if (settings.toolCallingEnabled)
                    "The assistant can use apps & services on this device"
                else "Disabled — the assistant only talks",
                checked = settings.toolCallingEnabled,
                onCheckedChange = { onUpdate(settings.copy(toolCallingEnabled = it)) }
            )

            if (settings.toolCallingEnabled) {
                HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.25f))

                // Confirmation mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Filled.Rule, contentDescription = null, tint = LampGlow, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Confirmations", style = MaterialTheme.typography.bodyMedium, color = DeskPaper)
                        Text(
                            "Which actions require your approval",
                            style = MaterialTheme.typography.bodySmall,
                            color = DeskInk
                        )
                    }
                }
                ConfirmationMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdate(settings.copy(confirmationMode = mode)) }
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (settings.confirmationMode == mode) "●" else "○",
                            color = if (settings.confirmationMode == mode) LampAmber else DeskInkFaint,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                mode.displayName,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (settings.confirmationMode == mode) DeskPaper else DeskInk,
                                    fontWeight = if (settings.confirmationMode == mode) FontWeight.Medium else FontWeight.Normal
                                )
                            )
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall.copy(color = DeskInkFaint)
                            )
                        }
                    }
                }

                HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.15f))

                AutoToggleRow(
                    icon = Icons.Filled.Mic,
                    title = "Voice confirmations",
                    subtitle = "Voice mode asks aloud and listens for yes/no",
                    checked = settings.voiceConfirmations,
                    onCheckedChange = { onUpdate(settings.copy(voiceConfirmations = it)) }
                )

                HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.15f))

                // Device permissions the tools need at the Android level
                if (missingDevicePermissions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = LampGlow, modifier = Modifier.size(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device permissions", style = MaterialTheme.typography.bodyMedium, color = DeskPaper)
                            Text(
                                "Some tools need system permissions before they can run",
                                style = MaterialTheme.typography.bodySmall,
                                color = DeskInk
                            )
                        }
                    }
                    missingDevicePermissions.forEach { (label, perms) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall.copy(color = DeskPaper),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Not granted",
                                style = MaterialTheme.typography.labelSmall.copy(color = LampAmber)
                            )
                            TextButton(onClick = { onRequestPermissions(perms) }) {
                                Text("Grant", color = LampGlow)
                            }
                        }
                    }
                    HorizontalDivider(color = DeskInkFaint.copy(alpha = 0.15f))
                }

                // Per-tool toggles grouped by category
                ToolCategory.entries.forEach { category ->
                    val categoryTools = tools.filter { it.spec.category == category }
                    if (categoryTools.isNotEmpty()) {
                        Text(
                            text = category.displayName.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = DeskInkFaint,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        categoryTools.sortedBy { it.spec.name }.forEach { tool ->
                            AutoToggleRow(
                                icon = Icons.Filled.Handshake,
                                title = tool.spec.permission?.displayName ?: tool.spec.name.replace('_', ' '),
                                subtitle = tool.spec.description.take(70),
                                checked = settings.isToolEnabled(tool.spec.name),
                                onCheckedChange = { enabled ->
                                    onUpdate(
                                        settings.copy(
                                            disabledTools = if (enabled) {
                                                settings.disabledTools - tool.spec.name
                                            } else {
                                                settings.disabledTools + tool.spec.name
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Enable Tool Calling to manage per-tool permissions.",
                    style = MaterialTheme.typography.bodySmall.copy(color = DeskInk),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * The Android runtime permissions backing the automation tools, by label.
 * Derived from every [ToolPermission] so this list can never drift from the
 * mapping the confirmation card uses — new tool categories (voice recorder,
 * …) automatically get a grant button here.
 */
private val permissionGroups: List<Pair<String, List<String>>> =
    ToolPermission.entries
        .map { it.displayName to it.runtimePermissions() }
        .filter { it.second.isNotEmpty() }

@Composable
private fun AutoToggleRow(
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
