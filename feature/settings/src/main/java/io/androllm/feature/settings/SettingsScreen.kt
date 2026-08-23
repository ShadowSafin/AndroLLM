package io.androllm.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.models.ChatFontSize
import io.androllm.core.models.ThemeMode
import io.androllm.core.models.UiDensity
import io.androllm.core.ui.components.CloudAccentOptions
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.utils.StorageUtils
import io.androllm.feature.settings.R
import io.androllm.core.ui.theme.ledger
import kotlin.math.roundToInt

/**
 * Writer's Night Desk — Settings. Identity, storage, motion and privacy,
 * all kept in walnut under the lamp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val logPreview by viewModel.logPreview.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val memorySettings by viewModel.memorySettings.collectAsStateWithLifecycle()
    val memoryStats by viewModel.memoryStats.collectAsStateWithLifecycle()
    val memoryMessage by viewModel.memoryMessage.collectAsStateWithLifecycle()
    val attachmentSettings by viewModel.attachmentSettings.collectAsStateWithLifecycle()
    val attachmentMessage by viewModel.attachmentMessage.collectAsStateWithLifecycle()
    val attachmentCacheBytes by viewModel.attachmentCacheBytes.collectAsStateWithLifecycle()
    val attachmentsSupported by viewModel.attachmentsSupported.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
    val voiceSettings by viewModel.voiceSettings.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val automationSettings by viewModel.automationSettings.collectAsStateWithLifecycle()
    val accessibilitySettings by viewModel.accessibilitySettings.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpStates by viewModel.mcpStates.collectAsStateWithLifecycle()
    val whisperModels by viewModel.whisperModels.collectAsStateWithLifecycle()
    val whisperInstalled by viewModel.whisperInstalled.collectAsStateWithLifecycle()
    val whisperDownload by viewModel.whisperDownload.collectAsStateWithLifecycle()
    val whisperMessage by viewModel.whisperMessage.collectAsStateWithLifecycle()
    val whisperStorageBytes = viewModel.whisperStorageBytes
    val overlayGranted = viewModel.overlayGranted
    val settings = (uiState as? UiState.Success)?.data ?: SettingsData()

    var showModelPathDialog by remember { mutableStateOf(false) }
    var showCloudEmbeddingDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.importMemories(uri) }

    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }

    fun toggleGroup(group: SettingsGroup) {
        expandedGroup = if (expandedGroup == group.name) null else group.name
    }

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) return@LaunchedEffect
        val match = SettingsGroup.entries.firstOrNull { it.matches(q) }
        if (match != null) expandedGroup = match.name
    }

    // Tool-calling runtime permissions (SMS, contacts, calls, calendar). The
    // tools fail fast without them, so Settings → Automation offers a grant
    // button; rows recompose away once the permission is granted.
    val automationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* nothing to do — the section recomposes from the grant state */ }

    LaunchedEffect(memoryMessage) {
        if (memoryMessage != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearMemoryMessage()
        }
    }

    CloudAtmosphericBackground(reduceMotion = settings.reduceMotion) {
        CloudAdaptiveNavigation(
            currentRoute = io.androllm.core.navigation.Routes.SETTINGS,
            onTabSelected = { tab ->
                if (tab.route != io.androllm.core.navigation.Routes.SETTINGS) {
                    navController.navigate(tab.route)
                }
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.ledger.deskPaper
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "settings-search") {
                    SettingsSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it }
                    )
                }
                item(key = "settings-quick-actions") {
                    SettingsQuickActions(
                        onClearCache = { viewModel.clearCache() },
                        onExportMemory = { viewModel.exportMemories() },
                        onImportMemory = {
                            importLauncher.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain")
                            )
                        },
                        onCheckUpdates = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/ShadowSafin/AndroLLM/releases")
                                    )
                                )
                            }
                        }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.Account,
                    icon = Icons.Filled.Person,
                    expanded = expandedGroup == SettingsGroup.Account.name,
                    onToggle = { toggleGroup(SettingsGroup.Account) },
                    visible = SettingsGroup.Account.matches(searchQuery),
                    subtitle = if (user?.isGuest == false) "Signed in" else "Guest · optional sync",
                    reduceMotion = settings.reduceMotion
                ) {
                    UserProfileCard(user = user)
                    UserStatsRow()
                    FirebaseAuthCard(
                        user = user,
                        onSignIn = { navController.navigate(io.androllm.core.navigation.Routes.AUTH) }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.Appearance,
                    icon = Icons.Filled.Palette,
                    expanded = expandedGroup == SettingsGroup.Appearance.name,
                    onToggle = { toggleGroup(SettingsGroup.Appearance) },
                    visible = SettingsGroup.Appearance.matches(searchQuery),
                    subtitle = settings.theme.displayName(),
                    reduceMotion = settings.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Palette,
                                title = stringResource(R.string.settings_theme),
                                value = settings.theme.displayName(),
                                onClick = { viewModel.cycleTheme() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Palette,
                                title = "Dynamic Color",
                                value = if (settings.dynamicColor) "On" else "Off",
                                onClick = { viewModel.setDynamicColor(!settings.dynamicColor) }
                            )
                            AccentSwatches(
                                selectedHex = settings.accentHex,
                                onSelect = { viewModel.setAccentColor(it) }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline.copy(alpha = 0.5f))
                            SettingRow(
                                icon = Icons.Filled.TextFields,
                                title = "Text Size",
                                value = settings.fontSize.displayName(),
                                onClick = { viewModel.cycleFontSize() }
                            )
                            SettingRow(
                                icon = Icons.Filled.SpaceBar,
                                title = "Interface Density",
                                value = settings.uiDensity.displayName(),
                                onClick = { viewModel.cycleUiDensity() }
                            )
                            HorizontalDivider(color = MaterialTheme.ledger.deskHairline.copy(alpha = 0.5f))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.BlurOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.ledger.lampDeep,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = "Background Blur",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.ledger.deskPaper
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${(settings.blurIntensity * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.ledger.deskInkFaint
                                        )
                                    )
                                }
                                Slider(
                                    value = settings.blurIntensity,
                                    onValueChange = { viewModel.setBlurIntensity(it) },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            SettingRow(
                                icon = Icons.Filled.MotionPhotosOn,
                                title = "Reduce Background Motion",
                                value = if (settings.reduceMotion) "Enabled" else "Disabled",
                                onClick = { viewModel.setReduceMotion(!settings.reduceMotion) }
                            )
                            SettingRow(
                                icon = Icons.Filled.Wallpaper,
                                title = "Chat Wallpaper",
                                value = if (settings.chatWallpaper.isBlank()) "Default" else "Custom",
                                onClick = { viewModel.setChatWallpaper(if (settings.chatWallpaper.isBlank()) "FF2A2A2A" else "") }
                            )
                        }
                    }
                }

                settingsAccordionItem(
                    group = SettingsGroup.Storage,
                    icon = Icons.Filled.Storage,
                    expanded = expandedGroup == SettingsGroup.Storage.name,
                    onToggle = { toggleGroup(SettingsGroup.Storage) },
                    visible = SettingsGroup.Storage.matches(searchQuery),
                    subtitle = storageStats?.let { "${StorageUtils.formatBytes(it.availableBytes)} free" },
                    reduceMotion = settings.reduceMotion
                ) {
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
                                title = "Free Space",
                                value = storageStats?.let {
                                    "${StorageUtils.formatBytes(it.availableBytes)} free"
                                } ?: "…",
                                onClick = { viewModel.refreshStorageStats() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = "Models on Device",
                                value = storageStats?.let {
                                    StorageUtils.formatBytes(it.usedBytes)
                                } ?: "…",
                                onClick = { viewModel.refreshStorageStats() }
                            )
                            SettingRow(
                                icon = Icons.Filled.Storage,
                                title = stringResource(R.string.settings_clear_cache),
                                onClick = { viewModel.clearCache() }
                            )
                        }
                    }
                }

                settingsAccordionItem(
                    group = SettingsGroup.Memory,
                    icon = Icons.Filled.Psychology,
                    expanded = expandedGroup == SettingsGroup.Memory.name,
                    onToggle = { toggleGroup(SettingsGroup.Memory) },
                    visible = SettingsGroup.Memory.matches(searchQuery),
                    subtitle = if (memorySettings.enabled) "On-device" else "Off",
                    reduceMotion = settings.reduceMotion
                ) {
                    MemorySettingsCard(
                        settings = memorySettings,
                        stats = memoryStats,
                        feedback = memoryMessage,
                        onToggleEnabled = { viewModel.toggleMemoryEnabled() },
                        onThresholdChange = { viewModel.updateSimilarityThreshold(it) },
                        onRetrievalCountChange = { viewModel.updateRetrievalCount(it) },
                        onSummarizationIntervalChange = { viewModel.updateSummarizationInterval(it) },
                        onModelPathClick = { showModelPathDialog = true },
                        onTestModel = { viewModel.testEmbeddingModel() },
                        onCloudEmbeddingClick = { showCloudEmbeddingDialog = true },
                        onExport = { viewModel.exportMemories() },
                        onImport = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        onDeleteAll = { viewModel.deleteAllMemories() },
                        onRefreshStats = { viewModel.refreshMemoryStats() },
                        onInspectorClick = {
                            navController.navigate(io.androllm.core.navigation.Routes.DEVELOPER)
                        }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.VoiceAssistant,
                    icon = Icons.Filled.RecordVoiceOver,
                    expanded = expandedGroup == SettingsGroup.VoiceAssistant.name,
                    onToggle = { toggleGroup(SettingsGroup.VoiceAssistant) },
                    visible = SettingsGroup.VoiceAssistant.matches(searchQuery),
                    subtitle = if (voiceSettings.enabled) "Listening" else "Off",
                    reduceMotion = settings.reduceMotion
                ) {
                    VoiceAssistantSection(
                        settings = voiceSettings,
                        liveState = voiceState,
                        overlayGranted = overlayGranted,
                        onUpdate = { viewModel.updateVoiceSettings(it) },
                        onStart = { viewModel.startVoiceAssistant() },
                        onStop = { viewModel.stopVoiceAssistant() },
                        onOpenOverlayPermission = { viewModel.openOverlayPermissionSettings() }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.SpeechRecognition,
                    icon = Icons.Filled.Mic,
                    expanded = expandedGroup == SettingsGroup.SpeechRecognition.name,
                    onToggle = { toggleGroup(SettingsGroup.SpeechRecognition) },
                    visible = SettingsGroup.SpeechRecognition.matches(searchQuery),
                    subtitle = "whisper.cpp",
                    reduceMotion = settings.reduceMotion
                ) {
                    SpeechRecognitionSection(
                        settings = voiceSettings,
                        models = whisperModels,
                        installedIds = whisperInstalled,
                        download = whisperDownload,
                        message = whisperMessage,
                        storageBytes = whisperStorageBytes,
                        onSelectModel = { viewModel.selectWhisperModel(it) },
                        onDownloadModel = { viewModel.downloadWhisperModel(it) },
                        onDeleteModel = { viewModel.deleteWhisperModel(it) },
                        onUpdate = { viewModel.updateVoiceSettings(it) }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.TextNormalization,
                    icon = Icons.Filled.TextFields,
                    expanded = expandedGroup == SettingsGroup.TextNormalization.name,
                    onToggle = { toggleGroup(SettingsGroup.TextNormalization) },
                    visible = SettingsGroup.TextNormalization.matches(searchQuery),
                    subtitle = if (voiceSettings.tnEnabled) "On" else "Off",
                    reduceMotion = settings.reduceMotion
                ) {
                    TextNormalizationSection(
                        settings = voiceSettings,
                        onUpdate = { viewModel.updateVoiceSettings(it) }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.Automation,
                    icon = Icons.Filled.Bolt,
                    expanded = expandedGroup == SettingsGroup.Automation.name,
                    onToggle = { toggleGroup(SettingsGroup.Automation) },
                    visible = SettingsGroup.Automation.matches(searchQuery),
                    subtitle = "Tool calling",
                    reduceMotion = settings.reduceMotion
                ) {
                    AutomationSection(
                        settings = automationSettings,
                        tools = viewModel.tools,
                        onUpdate = { viewModel.updateAutomationSettings(it) },
                        onRequestPermissions = { perms -> automationPermissionLauncher.launch(perms.toTypedArray()) }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.UiAutomation,
                    icon = Icons.Filled.AccessibilityNew,
                    expanded = expandedGroup == SettingsGroup.UiAutomation.name,
                    onToggle = { toggleGroup(SettingsGroup.UiAutomation) },
                    visible = SettingsGroup.UiAutomation.matches(searchQuery),
                    subtitle = "Accessibility",
                    reduceMotion = settings.reduceMotion
                ) {
                    AccessibilitySection(
                        settings = accessibilitySettings,
                        serviceEnabled = viewModel.accessibilityServiceEnabled,
                        connected = viewModel.accessibilityConnected,
                        onUpdate = { viewModel.updateAccessibilitySettings(it) },
                        onOpenSettings = { viewModel.openAccessibilitySettings() }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.DevicePermissions,
                    icon = Icons.Filled.Lock,
                    expanded = expandedGroup == SettingsGroup.DevicePermissions.name,
                    onToggle = { toggleGroup(SettingsGroup.DevicePermissions) },
                    visible = SettingsGroup.DevicePermissions.matches(searchQuery),
                    subtitle = "SMS, Phone, Calendar, Contacts…",
                    reduceMotion = settings.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.Lock,
                                title = "Permissions & Access",
                                value = "Microphone, notifications, accessibility…",
                                onClick = { navController.navigate(io.androllm.core.navigation.Routes.PERMISSIONS) }
                            )
                        }
                    }
                }

                settingsAccordionItem(
                    group = SettingsGroup.Mcp,
                    icon = Icons.Filled.Dns,
                    expanded = expandedGroup == SettingsGroup.Mcp.name,
                    onToggle = { toggleGroup(SettingsGroup.Mcp) },
                    visible = SettingsGroup.Mcp.matches(searchQuery),
                    subtitle = if (mcpServers.isEmpty()) "None" else "${mcpServers.size} server(s)",
                    reduceMotion = settings.reduceMotion
                ) {
                    McpSection(
                        servers = mcpServers,
                        states = mcpStates,
                        onAdd = { name, url, token -> viewModel.addMcpServer(name, url, token) },
                        onRemove = { viewModel.removeMcpServer(it) },
                        onToggle = { server, enabled -> viewModel.setMcpServerEnabled(server, enabled) }
                    )
                }

                settingsAccordionItem(
                    group = SettingsGroup.CloudProviders,
                    icon = Icons.Filled.CloudDone,
                    expanded = expandedGroup == SettingsGroup.CloudProviders.name,
                    onToggle = { toggleGroup(SettingsGroup.CloudProviders) },
                    visible = SettingsGroup.CloudProviders.matches(searchQuery),
                    subtitle = "LiteLLM",
                    reduceMotion = settings.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            SettingRow(
                                icon = Icons.Filled.CloudDone,
                                title = "LiteLLM Gateway",
                                value = "Manage providers & models",
                                onClick = { navController.navigate(io.androllm.core.navigation.Routes.CLOUD_PROVIDERS) }
                            )
                        }
                    }
                }

                if (attachmentsSupported) {
                    settingsAccordionItem(
                        group = SettingsGroup.ChatAttachments,
                        icon = Icons.Filled.AttachFile,
                        expanded = expandedGroup == SettingsGroup.ChatAttachments.name,
                        onToggle = { toggleGroup(SettingsGroup.ChatAttachments) },
                        visible = SettingsGroup.ChatAttachments.matches(searchQuery),
                        subtitle = "Conversation files",
                        reduceMotion = settings.reduceMotion
                    ) {
                        AttachmentSettingsCard(
                            settings = attachmentSettings,
                            feedback = attachmentMessage,
                            cacheBytes = attachmentCacheBytes,
                            onImageQualityChange = { value -> viewModel.updateAttachmentSettings { it.copy(imageQuality = value) } },
                            onOcrLanguageChange = { value -> viewModel.updateAttachmentSettings { it.copy(ocrLanguage = value) } },
                            onMaxSizeChange = { value -> viewModel.updateAttachmentSettings { it.copy(maxAttachmentBytes = value) } },
                            onMaxPerMessageChange = { value -> viewModel.updateAttachmentSettings { it.copy(maxAttachmentsPerMessage = value) } },
                            onAutoCompressChange = { value -> viewModel.updateAttachmentSettings { it.copy(autoCompressImages = value) } },
                            onPreserveFilenamesChange = { value -> viewModel.updateAttachmentSettings { it.copy(preserveFilenames = value) } },
                            onCacheProcessedChange = { value -> viewModel.updateAttachmentSettings { it.copy(cacheProcessedAttachments = value) } },
                            onClearCache = { viewModel.clearAttachmentCache() }
                        )
                    }
                }

                settingsAccordionItem(
                    group = SettingsGroup.Safety,
                    icon = Icons.Filled.Security,
                    expanded = expandedGroup == SettingsGroup.Safety.name,
                    onToggle = { toggleGroup(SettingsGroup.Safety) },
                    visible = SettingsGroup.Safety.matches(searchQuery),
                    subtitle = if (settings.warnBeforeOpeningAiLinks) "Warnings on" else "Warnings off",
                    reduceMotion = settings.reduceMotion
                ) {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleWarnBeforeOpeningAiLinks() }
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.ledger.lampDeep,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Warn before opening AI links",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.ledger.deskPaper
                                        )
                                    )
                                    Text(
                                        text = "Show a confirmation before opening external links found by AI. Highly recommended.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.ledger.deskInk
                                        )
                                    )
                                }
                                Switch(
                                    checked = settings.warnBeforeOpeningAiLinks,
                                    onCheckedChange = { viewModel.setWarnBeforeOpeningAiLinks(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.ledger.lampAmber)
                                )
                            }
                        }
                    }
                }

                settingsAccordionItem(
                    group = SettingsGroup.Developer,
                    icon = Icons.Filled.Code,
                    expanded = expandedGroup == SettingsGroup.Developer.name,
                    onToggle = { toggleGroup(SettingsGroup.Developer) },
                    visible = SettingsGroup.Developer.matches(searchQuery),
                    subtitle = if (settings.developerMode) "On" else "Off",
                    reduceMotion = settings.reduceMotion
                ) {
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
                                color = MaterialTheme.ledger.deskInk,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                settingsAccordionItem(
                    group = SettingsGroup.About,
                    icon = Icons.Filled.Info,
                    expanded = expandedGroup == SettingsGroup.About.name,
                    onToggle = { toggleGroup(SettingsGroup.About) },
                    visible = SettingsGroup.About.matches(searchQuery),
                    subtitle = "3.0.0",
                    reduceMotion = settings.reduceMotion
                ) {
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

                item(key = "settings-end-spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showModelPathDialog) {
        ModelPathDialog(
            currentPath = memorySettings.embeddingModelPath,
            onDismiss = { showModelPathDialog = false },
            onSave = { path ->
                showModelPathDialog = false
                viewModel.setEmbeddingModelPath(path)
            }
        )
    }

    if (showCloudEmbeddingDialog) {
        CloudEmbeddingModelDialog(
            currentModel = memorySettings.cloudEmbeddingModel,
            onDismiss = { showCloudEmbeddingDialog = false },
            onSave = { modelId ->
                showCloudEmbeddingDialog = false
                viewModel.setCloudEmbeddingModel(modelId)
            }
        )
    }
}

/**
 * Large Floating User Profile Header Card.
 */
@Composable
private fun UserProfileCard(user: SettingsIdentity?) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CloudBugdroidLogo(size = 72.dp)
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.displayName?.takeIf { it.isNotBlank() } ?: "AndroLLM User",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = user?.displayName?.takeIf { it.isNotBlank() } ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.ledger.deskInkFaint
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = if (user?.isGuest == false) {
                        user?.email ?: ""
                    } else {
                        "Guest • 100% on-device"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (user?.isGuest == false) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInkFaint
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                Text("Downloaded", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk))
                Text("3 Models", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper))
            }
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Storage", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk))
                Text("4.2 GB", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper))
            }
        }
        CloudGlassCard(modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Execution", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk))
                Text("Vulkan", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.lampDeep))
            }
        }
    }
}

/**
 * Account & Sync Section.
 */
@Composable
private fun FirebaseAuthCard(
    user: SettingsIdentity?,
    onSignIn: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Account & Sync",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = if (user?.isGuest == false) {
                            "Synced as ${user?.email ?: "your account"}"
                        } else {
                            "Syncing is optional — offline AI never requires a login"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInk
                        ),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                CloudChip(
                    text = if (user?.isGuest == false) "Signed In" else "Optional",
                    accentColor = if (user?.isGuest == false) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            CloudCapsuleButton(
                text = if (user?.isGuest == false) "Manage Account" else "Sign in with Google",
                onClick = onSignIn,
                icon = Icons.Filled.AccountCircle,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun SettingRow(
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
            tint = MaterialTheme.ledger.lampDeep,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.ledger.deskPaper
            ),
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.ledger.deskInkFaint
                )
            )
        }
    }
}

/**
 * On-device Memory — master switch, similarity threshold, retrieval count,
 * embedding model wiring, export/import and full wipe.
 */
@Composable
private fun MemorySettingsCard(
    settings: io.androllm.core.memory.model.MemorySettings,
    stats: io.androllm.core.memory.model.MemoryInspectorStats?,
    feedback: String?,
    onToggleEnabled: () -> Unit,
    onThresholdChange: (Float) -> Unit,
    onRetrievalCountChange: (Int) -> Unit,
    onSummarizationIntervalChange: (Int) -> Unit,
    onModelPathClick: () -> Unit,
    onTestModel: () -> Unit,
    onCloudEmbeddingClick: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDeleteAll: () -> Unit,
    onRefreshStats: () -> Unit,
    onInspectorClick: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            // Master switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleEnabled)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint = if (settings.enabled) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.lampGlow,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "On-device Memory",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = "Personalized replies from your own conversations — never leaves this device",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.ledger.lampAmber)
                )
            }

            if (settings.enabled) {
                HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.25f))

                // Stats line
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stats?.let { "${it.memoryCount} memories • ${it.embeddingCount} embeddings" } ?: "…",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefreshStats) {
                        Text("Refresh", color = MaterialTheme.ledger.lampGlow)
                    }
                }

                if (feedback != null) {
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.lampAmber),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Similarity threshold
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Similarity threshold  ${(settings.similarityThreshold * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                    Slider(
                        value = settings.similarityThreshold,
                        onValueChange = onThresholdChange,
                        valueRange = io.androllm.core.memory.model.MemorySettings.THRESHOLD_MIN..io.androllm.core.memory.model.MemorySettings.THRESHOLD_MAX,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Retrieval count
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Memories retrieved per prompt: ${settings.retrievalCount}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                    Slider(
                        value = settings.retrievalCount.toFloat(),
                        onValueChange = { onRetrievalCountChange(it.toInt()) },
                        valueRange = io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MIN.toFloat()..
                            io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MAX.toFloat(),
                        steps = io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MAX -
                            io.androllm.core.memory.model.MemorySettings.RETRIEVAL_MIN - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Summarization interval
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Summarize every ${settings.summarizationInterval} messages",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                    Slider(
                        value = settings.summarizationInterval.toFloat(),
                        onValueChange = { onSummarizationIntervalChange(it.toInt()) },
                        valueRange = io.androllm.core.memory.model.MemorySettings.SUMMARIZATION_MIN.toFloat()..
                            io.androllm.core.memory.model.MemorySettings.SUMMARIZATION_MAX.toFloat(),
                        steps = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Embedding model
                SettingRow(
                    icon = Icons.Filled.Tune,
                    title = "Embedding model",
                    value = settings.embeddingModelPath.substringAfterLast('/')
                        .takeIf { it.isNotBlank() }
                        ?: "Not configured",
                    onClick = onModelPathClick
                )
                SettingRow(
                    icon = Icons.Filled.PlayArrow,
                    title = "Test embedding model",
                    value = if (stats?.embeddingModelLoaded == true) "Loaded (dim ${stats.embeddingDimension})" else null,
                    onClick = onTestModel
                )
                SettingRow(
                    icon = Icons.Filled.CloudDone,
                    title = "Cloud embedding model",
                    value = settings.cloudEmbeddingModel.substringAfterLast('/')
                        .takeIf { it.isNotBlank() }
                        ?: "Not configured",
                    onClick = onCloudEmbeddingClick
                )

                HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.25f))

                SettingRow(
                    icon = Icons.Filled.IosShare,
                    title = "Export memories",
                    onClick = onExport
                )
                SettingRow(
                    icon = Icons.Filled.FileUpload,
                    title = "Import memories",
                    onClick = onImport
                )
                SettingRow(
                    icon = Icons.Filled.Psychology,
                    title = "Memory Inspector",
                    value = "Open developer dashboard",
                    onClick = onInspectorClick
                )
                SettingRow(
                    icon = Icons.Filled.Delete,
                    title = "Delete all memories",
                    onClick = onDeleteAll
                )
            }
        }
    }
}

/**
 * Small dialog for entering the absolute path of the local LiteRT embedding
 * model (.tflite). The LiteRT engine expects the Gemma 3 `tokenizer.model`
 * next to the file (downloaded automatically with the catalog model).
 */
@Composable
private fun ModelPathDialog(
    currentPath: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var path by remember { mutableStateOf(currentPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Embedding model path", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
        text = {
            Column {
                Text(
                    text = "Absolute path to the EmbeddingGemma 300M .tflite model (downloaded from the Models screen Catalog). A tokenizer.model must sit next to it — the app downloads both automatically.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Model path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(path.trim()) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.lampAmber)
            ) {
                Text("Save", color = MaterialTheme.ledger.deskPaper)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.ledger.deskInkFaint) }
        }
    )
}

/**
 * Small dialog for configuring the cloud embedding model id, routed through
 * the active LiteLLM provider. Empty clears the cloud route.
 */
@Composable
private fun CloudEmbeddingModelDialog(
    currentModel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var model by remember { mutableStateOf(currentModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud embedding model", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
        text = {
            Column {
                Text(
                    text = "Model id for /v1/embeddings through your active cloud provider (e.g. openai/text-embedding-3-small, cohere/embed-english-v3.0, togethertext-embedding...). Leave empty to use the local LiteRT embedding model.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Embedding model id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(model.trim()) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.lampAmber)
            ) {
                Text("Save", color = MaterialTheme.ledger.deskPaper)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.ledger.deskInkFaint) }
        }
    )
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
}

private fun UiDensity.displayName(): String = when (this) {
    UiDensity.COMPACT -> "Compact"
    UiDensity.DEFAULT -> "Default"
    UiDensity.COMFORTABLE -> "Comfortable"
}

private fun ChatFontSize.displayName(): String = when (this) {
    ChatFontSize.SMALL -> "Small"
    ChatFontSize.MEDIUM -> "Medium"
    ChatFontSize.LARGE -> "Large"
}

/**
 * Six terracotta-family accents, one per row, with the active one ringed in
 * lamp glow. Tapping writes the hex straight to preferences; the theme
 * recomposes app-wide from MainActivity.
 */
@Composable
private fun AccentSwatches(
    selectedHex: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CloudAccentOptions.forEach { accent ->
            val isSelected = selectedHex.equals(accent.argbHex, ignoreCase = true) ||
                (selectedHex.isBlank() && accent.argbHex == "FFD97757")
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskHairline,
                        shape = CircleShape
                    )
                    .clickable { onSelect(accent.argbHex) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private fun Boolean.displayYesNo(): String = if (this) "Yes" else "No"

/**
 * Knowledge Base (RAG) settings card: master switch, chunking, retrieval,
 * OCR language, auto/background indexing and embedding-source testing. All
 * values are plain on-device preferences.
 */
/**
 * Chat Attachment settings card: image quality, OCR language, size limits,
 * auto-compression, filename handling and the conversation-scoped cache.
 * There is no document index anymore — these only govern how a picked file
 * is processed for the current chat.
 */
@Composable
private fun AttachmentSettingsCard(
    settings: io.androllm.core.attachments.model.AttachmentSettings,
    feedback: String?,
    cacheBytes: Long,
    onImageQualityChange: (Int) -> Unit,
    onOcrLanguageChange: (String) -> Unit,
    onMaxSizeChange: (Long) -> Unit,
    onMaxPerMessageChange: (Int) -> Unit,
    onAutoCompressChange: (Boolean) -> Unit,
    onPreserveFilenamesChange: (Boolean) -> Unit,
    onCacheProcessedChange: (Boolean) -> Unit,
    onClearCache: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.lampAmber,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Chat Attachments",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.ledger.deskPaper
                        )
                    )
                    Text(
                        text = "Files you attach to a cloud chat are processed only for that conversation — nothing is indexed",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.25f))

            if (feedback != null) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.lampAmber),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Image quality
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "Image processing quality: ${settings.imageQuality}",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
                Slider(
                    value = settings.imageQuality.toFloat(),
                    onValueChange = { onImageQualityChange(it.toInt()) },
                    valueRange = io.androllm.core.attachments.model.AttachmentSettings.IMAGE_QUALITY_MIN.toFloat()..
                        io.androllm.core.attachments.model.AttachmentSettings.IMAGE_QUALITY_MAX.toFloat(),
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // OCR language
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "OCR language",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.weight(1f)
                )
                listOf("en", "de", "fr", "es", "it", "pt").forEach { lang ->
                    val selected = settings.ocrLanguage == lang
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) MaterialTheme.ledger.lampAmber.copy(alpha = 0.2f) else MaterialTheme.ledger.deskHairline.copy(alpha = 0.3f),
                                CircleShape
                            )
                            .clickable { onOcrLanguageChange(lang) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = lang.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (selected) MaterialTheme.ledger.lampGlow else MaterialTheme.ledger.deskInk
                            )
                        )
                    }
                }
            }

            // Maximum attachment size
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "Maximum file size: ${settings.maxAttachmentBytes / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
                Slider(
                    value = (settings.maxAttachmentBytes / (1024 * 1024)).toFloat(),
                    onValueChange = { onMaxSizeChange((it.toLong() * 1024 * 1024)) },
                    valueRange = 1f..50f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Max attachments per message
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "Max attachments per message: ${settings.maxAttachmentsPerMessage}",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                )
                Slider(
                    value = settings.maxAttachmentsPerMessage.toFloat(),
                    onValueChange = { onMaxPerMessageChange(it.toInt()) },
                    valueRange = io.androllm.core.attachments.model.AttachmentSettings.MAX_ATTACHMENTS_MIN.toFloat()..
                        io.androllm.core.attachments.model.AttachmentSettings.MAX_ATTACHMENTS_MAX.toFloat(),
                    steps = io.androllm.core.attachments.model.AttachmentSettings.MAX_ATTACHMENTS_MAX -
                        io.androllm.core.attachments.model.AttachmentSettings.MAX_ATTACHMENTS_MIN - 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Auto-compress images
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAutoCompressChange(!settings.autoCompressImages) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-compress images",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskPaper)
                    )
                    Text(
                        text = "Downscale photos before sending to vision models",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                }
                Switch(
                    checked = settings.autoCompressImages,
                    onCheckedChange = onAutoCompressChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.ledger.lampAmber)
                )
            }

            // Preserve filenames
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPreserveFilenamesChange(!settings.preserveFilenames) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Preserve original filenames",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskPaper)
                    )
                    Text(
                        text = "Keep the source name for copied attachments",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                }
                Switch(
                    checked = settings.preserveFilenames,
                    onCheckedChange = onPreserveFilenamesChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.ledger.lampAmber)
                )
            }

            // Cache processed attachments (current conversation only)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCacheProcessedChange(!settings.cacheProcessedAttachments) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cache processed attachments",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskPaper)
                    )
                    Text(
                        text = "Keep parsed text for the current conversation only",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                }
                Switch(
                    checked = settings.cacheProcessedAttachments,
                    onCheckedChange = onCacheProcessedChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.ledger.lampAmber)
                )
            }

            // Cache usage + clear
            HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.25f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Temporary cache",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskPaper)
                    )
                    Text(
                        text = if (cacheBytes > 0) {
                            io.androllm.core.attachments.model.ChatAttachment.formatSize(cacheBytes) + " in use"
                        } else {
                            "Nothing cached"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                }
                TextButton(onClick = onClearCache) {
                    Text("Clear cache", color = MaterialTheme.ledger.lampGlow)
                }
            }
        }
    }
}
