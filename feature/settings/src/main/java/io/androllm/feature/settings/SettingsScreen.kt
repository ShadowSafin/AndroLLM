package io.androllm.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.models.ThemeMode
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.AuroraCyan
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.ElectricBlue
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SoftCyan
import io.androllm.feature.settings.R

/**
 * Cloud Intelligence Profile & Settings Screen.
 * User Avatar, Firebase Authentication & Guest Mode status, Stats Cards, and Preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logPreview by viewModel.logPreview.collectAsStateWithLifecycle()
    val settings = (uiState as? UiState.Success)?.data ?: SettingsData()

    var reduceMotion by remember { mutableStateOf(false) }

    CloudAtmosphericBackground(reduceMotion = reduceMotion) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = CloudWhite
                            )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. User Profile Header Card
                item {
                    UserProfileCard()
                }

                // 2. User Statistics Cards
                item {
                    UserStatsRow()
                }

                // 3. Firebase Authentication Section
                item {
                    FirebaseAuthCard()
                }

                // 4. Appearance & Motion
                item {
                    SectionHeader(title = stringResource(R.string.settings_appearance))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Palette,
                                title = stringResource(R.string.settings_theme),
                                value = settings.theme.displayName(),
                                onClick = { viewModel.cycleTheme() }
                            )
                            SettingRow(
                                icon = Icons.Filled.MotionPhotosOn,
                                title = "Reduce Background Motion",
                                value = if (reduceMotion) "Enabled" else "Disabled",
                                onClick = { reduceMotion = !reduceMotion }
                            )
                        }
                    }
                }

                // 5. Storage
                item {
                    SectionHeader(title = stringResource(R.string.settings_storage))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = stringResource(R.string.settings_storage_path),
                                value = settings.storagePath.ifEmpty { "Internal Storage" },
                                onClick = {}
                            )
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = stringResource(R.string.settings_clear_cache),
                                onClick = { viewModel.clearCache() }
                            )
                        }
                    }
                }

                // 6. Developer Options
                item {
                    SectionHeader(title = stringResource(R.string.settings_developer))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Code,
                                title = stringResource(R.string.settings_developer_mode),
                                value = settings.developerMode.displayYesNo(),
                                onClick = { viewModel.toggleDeveloperMode() }
                            )
                        }
                    }
                }

                // 7. Logs & Diagnostics
                item {
                    SectionHeader(title = stringResource(R.string.settings_logs))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Settings,
                                title = stringResource(R.string.settings_export_logs),
                                onClick = { viewModel.exportLogs() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Refresh,
                                title = stringResource(R.string.settings_refresh_logs),
                                onClick = { viewModel.refreshLogPreview() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = logPreview.ifBlank { stringResource(R.string.settings_logs_empty) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MoonSilver.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 8. About & Privacy
                item {
                    SectionHeader(title = stringResource(R.string.settings_about))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Info,
                                title = stringResource(R.string.settings_version),
                                value = "3.0.0 (Cloud Intelligence)",
                                onClick = {}
                            )
                            SettingRow(
                                icon = Icons.Filled.Security,
                                title = "Privacy Guarantee",
                                value = "100% Offline AI",
                                onClick = {}
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * Large Floating User Profile Header Card.
 */
@Composable
private fun UserProfileCard() {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CloudBugdroidLogo(size = 72.dp)
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AndroLLM User",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = CloudWhite
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Anonymous Guest • Offline AI Enabled",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SoftCyan
                    )
                )
            }
        }
    }
}

/**
 * User Statistics Row.
 */
@Composable
private fun UserStatsRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Downloaded", style = MaterialTheme.typography.labelSmall.copy(color = MoonSilver.copy(alpha = 0.6f)))
                Text("3 Models", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = CloudWhite))
            }
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Storage", style = MaterialTheme.typography.labelSmall.copy(color = MoonSilver.copy(alpha = 0.6f)))
                Text("4.2 GB", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = CloudWhite))
            }
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Execution", style = MaterialTheme.typography.labelSmall.copy(color = MoonSilver.copy(alpha = 0.6f)))
                Text("Vulkan", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = SoftCyan))
            }
        }
    }
}

/**
 * Firebase Authentication & Cloud Sync Section.
 */
@Composable
private fun FirebaseAuthCard() {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Firebase Cloud Account",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = CloudWhite
                        )
                    )
                    Text(
                        text = "Cloud sync is optional. Offline AI never requires login.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MoonSilver.copy(alpha = 0.7f)
                        )
                    )
                }
                CloudChip(text = "Optional", accentColor = SoftCyan)
            }
            Spacer(modifier = Modifier.height(16.dp))
            CloudCapsuleButton(
                text = "Sign in with Google",
                onClick = {},
                icon = Icons.Filled.AccountCircle,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SkyBlue,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                color = CloudWhite
            ),
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MoonSilver.copy(alpha = 0.7f)
                )
            )
        }
    }
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun Boolean.displayYesNo(): String = if (this) "Yes" else "No"
