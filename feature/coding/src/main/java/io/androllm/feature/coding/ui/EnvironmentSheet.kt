package io.androllm.feature.coding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.environment.LinuxBasePhase
import io.androllm.feature.coding.environment.MarketplaceCatalog

/**
 * Environment panel: shows the attached workspace, the Linux base status, and the
 * installed addons with their PATH entries. Offers provisioning of the full base.
 */
@Composable
fun EnvironmentSheet(
    workspacePath: String,
    installed: Set<String>,
    reviewMajorEdits: Boolean,
    onReviewMajorEdits: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onProvisionBase: () -> Unit,
    viewModel: CodingChatViewModel = hiltViewModel()
) {
    val baseStatus by viewModel.linuxBaseStatus.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { SectionHeader(title = "Environment", subtitle = "Linux CLI attached to workspace") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoRow("Workspace", workspacePath)
                InfoRow(
                    "Shell",
                    if (baseStatus.installed) "proot + Debian Linux (real runtimes)" else "sh -c (device userland)"
                )
                InfoRow(
                    "Base",
                    when (baseStatus.phase) {
                        LinuxBasePhase.READY -> "ready (Debian)"
                        LinuxBasePhase.FAILED -> "failed (retry)"
                        LinuxBasePhase.IDLE -> "not provisioned"
                        else -> "provisioning ${baseStatus.percent}%"
                    }
                )

                // Diff-review gate toggle: when on, large file changes must be approved.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Review major edits",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.ledger.deskPaper
                            )
                        )
                        Text(
                            "Ask before applying large file changes",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                        )
                    }
                    Switch(
                        checked = reviewMajorEdits,
                        onCheckedChange = onReviewMajorEdits,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.ledger.deskNight,
                            checkedTrackColor = MaterialTheme.ledger.lampAmber,
                            uncheckedThumbColor = MaterialTheme.ledger.deskInkFaint,
                            uncheckedTrackColor = MaterialTheme.ledger.deskHairline
                        )
                    )
                }

                CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            "Installed addons (${installed.size})",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.ledger.deskPaper
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        if (installed.isEmpty()) {
                            Text(
                                "None yet — only the base shell is available. Install runtimes from the marketplace.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                            )
                        } else {
                            installed.sorted().forEach { id ->
                                val pkg = MarketplaceCatalog.find(id)
                                Text(
                                    "• ${pkg?.name ?: id} ${pkg?.version ?: ""} → ${pkg?.providesCommands?.joinToString(", ").orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                if (baseStatus.phase != LinuxBasePhase.READY) {
                    Text(
                        "The Linux base is a real Debian userland run via proot. Once provisioned, " +
                            "marketplace addons install genuine runtimes (npm, python, git, ...) with apt, " +
                            "so your commands actually execute. First install downloads ~90 MB.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                    CloudCapsuleButton(
                        text = when (baseStatus.phase) {
                            LinuxBasePhase.FAILED -> "Retry base install"
                            LinuxBasePhase.IDLE -> "Install Linux base"
                            else -> "Installing... ${baseStatus.percent}%"
                        },
                        onClick = onProvisionBase
                    )
                }
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.ledger.deskPaper
            ),
            fontFamily = FontFamily.Monospace
        )
    }
}
