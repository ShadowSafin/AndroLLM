package io.androllm.feature.coding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.environment.InstallStatus
import io.androllm.feature.coding.environment.MarketplaceCatalog
import io.androllm.feature.coding.environment.PackageKind
import io.androllm.feature.coding.environment.RuntimePackage

/**
 * Marketplace / addon installer. Lists every runtime/toolchain addon with name,
 * description, version, size, enabled commands, platform, internet requirement
 * and live install status; install / retry / uninstall are one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceSheet(
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
    onRetry: (String) -> Unit,
    onUninstall: (String) -> Unit,
    viewModel: CodingChatViewModel = hiltViewModel()
) {
    val installed by viewModel.installedAddons.collectAsStateWithLifecycle()
    val progress by viewModel.installProgress.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            SectionHeader(title = "Marketplace", subtitle = "Runtimes & dev addons")
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                MarketplaceCatalog.groupedByKind().forEach { (kind, pkgs) ->
                    item {
                        Text(
                            kindLabel(kind),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.ledger.lampDeep,
                                letterSpacing = 1.2.sp
                            )
                        )
                    }
                    items(pkgs, key = { it.id }) { pkg ->
                        AddonCard(
                            pkg = pkg,
                            isInstalled = pkg.id in installed,
                            progress = progress[pkg.id],
                            onInstall = { onInstall(pkg.id) },
                            onRetry = { onRetry(pkg.id) },
                            onUninstall = { onUninstall(pkg.id) }
                        )
                    }
                }
            }
        }
    )
}

private fun kindLabel(kind: PackageKind): String = when (kind) {
    PackageKind.RUNTIME -> "RUNTIMES"
    PackageKind.PACKAGE_MANAGER -> "PACKAGE MANAGERS"
    PackageKind.VERSION_CONTROL -> "VERSION CONTROL"
    PackageKind.BUILD_TOOL -> "BUILD TOOLS"
    PackageKind.UTILITY -> "UTILITIES"
}

@Composable
private fun AddonCard(
    pkg: RuntimePackage,
    isInstalled: Boolean,
    progress: io.androllm.feature.coding.environment.InstallProgress?,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onUninstall: () -> Unit
) {
    val inFlight = progress != null && progress.status in setOf(InstallStatus.DOWNLOADING, InstallStatus.EXTRACTING)
    val failed = progress?.status == InstallStatus.FAILED

    CloudGlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .width(32.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(
                            if (isInstalled) MaterialTheme.ledger.revolutNeonEmerald.copy(alpha = 0.12f)
                            else MaterialTheme.ledger.lampAmber.copy(alpha = 0.10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Extension,
                        contentDescription = null,
                        tint = if (isInstalled) MaterialTheme.ledger.revolutNeonEmerald else MaterialTheme.ledger.lampAmber,
                        modifier = Modifier.height(18.dp).width(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    pkg.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (isInstalled) {
                    CloudChip(text = "installed", accentColor = MaterialTheme.ledger.revolutNeonEmerald, icon = Icons.Filled.CheckCircle)
                } else {
                    CloudChip(text = pkg.version, accentColor = MaterialTheme.ledger.deskInk)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(pkg.description, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk, lineHeight = 16.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                "enables: ${pkg.providesCommands.joinToString(", ")}  •  ${formatSize(pkg.sizeBytes)}" +
                    if (pkg.requiresInternet) "  •  needs internet" else "",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
            )
            if (pkg.dependsOn.isNotEmpty()) {
                Text(
                    "requires: ${pkg.dependsOn.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                )
            }

            if (inFlight) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (progress!!.percent / 100f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.ledger.lampAmber
                )
                Text(progress!!.message, style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.lampDeep))
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isInstalled -> CloudCapsuleButton(text = "Uninstall", onClick = onUninstall, modifier = Modifier.weight(1f))
                    failed -> CloudCapsuleButton(text = "Retry", onClick = onRetry, icon = Icons.Filled.Refresh, modifier = Modifier.weight(1f))
                    inFlight -> CloudCapsuleButton(text = "Installing…", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                    else -> CloudCapsuleButton(text = "Install", onClick = onInstall, icon = Icons.Filled.CloudDownload, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
