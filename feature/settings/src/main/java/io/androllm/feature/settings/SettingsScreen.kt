package io.androllm.feature.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.models.ThemeMode
import io.androllm.core.ui.components.SectionCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.brandPrimary
import io.androllm.feature.settings.R

/**
 * Settings screen: appearance, storage, developer options and about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = (uiState as? UiState.Success)?.data ?: SettingsData()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader(title = stringResource(R.string.settings_appearance))
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingRow(
                            icon = Icons.Filled.Palette,
                            title = stringResource(R.string.settings_theme),
                            value = settings.theme.displayName(),
                            onClick = { viewModel.cycleTheme() }
                        )
                        SettingRow(
                            icon = Icons.Filled.Palette,
                            title = stringResource(R.string.settings_language),
                            value = settings.language.uppercase(),
                            onClick = {}
                        )
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_storage))
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingRow(
                            icon = Icons.Filled.Storage,
                            title = stringResource(R.string.settings_storage_path),
                            value = settings.storagePath.ifEmpty { "Default" },
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

            item {
                SectionHeader(title = stringResource(R.string.settings_developer))
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingRow(
                            icon = Icons.Filled.Code,
                            title = stringResource(R.string.settings_developer_mode),
                            value = settings.developerMode.displayYesNo(),
                            onClick = { viewModel.toggleDeveloperMode() }
                        )
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.settings_about))
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SettingRow(
                            icon = Icons.Filled.Info,
                            title = stringResource(R.string.settings_version),
                            value = settings.versionName,
                            onClick = {}
                        )
                        SettingRow(
                            icon = Icons.Filled.Info,
                            title = stringResource(R.string.settings_licenses),
                            onClick = {}
                        )
                        SettingRow(
                            icon = Icons.Filled.Info,
                            title = stringResource(R.string.settings_privacy),
                            onClick = {}
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single settings row with icon, title, value and click handler.
 */
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = brandPrimary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Displays the theme mode as a label.
 */
private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/**
 * Displays a boolean as Yes/No.
 */
private fun Boolean.displayYesNo(): String = if (this) "Yes" else "No"
