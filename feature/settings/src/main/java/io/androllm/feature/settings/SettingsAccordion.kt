package io.androllm.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger

internal const val SETTINGS_ACCORDION_DURATION_MS = 220

/**
 * Exclusive accordion ids for Settings. Null means every group is collapsed.
 */
enum class SettingsGroup {
    Account,
    Appearance,
    Storage,
    Memory,
    VoiceAssistant,
    SpeechRecognition,
    TextNormalization,
    Automation,
    UiAutomation,
    DevicePermissions,
    Mcp,
    CloudProviders,
    ChatAttachments,
    Safety,
    Developer,
    About,
}

internal fun SettingsGroup.matches(query: String, extraKeywords: List<String> = emptyList()): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return title.contains(q, ignoreCase = true) ||
        keywords.any { it.contains(q, ignoreCase = true) } ||
        extraKeywords.any { it.contains(q, ignoreCase = true) }
}

internal val SettingsGroup.title: String
    get() = when (this) {
        SettingsGroup.Account -> "Account & Sync"
        SettingsGroup.Appearance -> "Appearance"
        SettingsGroup.Storage -> "Storage"
        SettingsGroup.Memory -> "Memory"
        SettingsGroup.VoiceAssistant -> "Voice Assistant"
        SettingsGroup.SpeechRecognition -> "Speech Recognition"
        SettingsGroup.TextNormalization -> "Text Normalization"
        SettingsGroup.Automation -> "Automation / Tool Calling"
        SettingsGroup.UiAutomation -> "UI Automation"
        SettingsGroup.DevicePermissions -> "Device Permissions"
        SettingsGroup.Mcp -> "MCP Servers"
        SettingsGroup.CloudProviders -> "Cloud Providers"
        SettingsGroup.ChatAttachments -> "Chat Attachments"
        SettingsGroup.Safety -> "Safety & Links"
        SettingsGroup.Developer -> "Developer Options"
        SettingsGroup.About -> "About"
    }

internal val SettingsGroup.keywords: List<String>
    get() = when (this) {
        SettingsGroup.Account -> listOf(
            "sign in", "google", "sync", "profile", "guest", "account", "firebase", "email"
        )
        SettingsGroup.Appearance -> listOf(
            "theme", "dynamic color", "accent", "text size", "density", "blur",
            "motion", "wallpaper", "font", "dark", "amoled", "light"
        )
        SettingsGroup.Storage -> listOf(
            "cache", "free space", "models", "path", "storage", "disk"
        )
        SettingsGroup.Memory -> listOf(
            "embedding", "similarity", "retrieval", "summarize", "export", "import",
            "inspector", "memories", "on-device"
        )
        SettingsGroup.VoiceAssistant -> listOf(
            "wake word", "overlay", "hands-free", "assistant", "tts", "voice"
        )
        SettingsGroup.SpeechRecognition -> listOf(
            "whisper", "model", "download", "language", "stt", "recognition", "debug"
        )
        SettingsGroup.TextNormalization -> listOf(
            "numbers", "dates", "tts", "speech", "normalize", "abbreviation"
        )
        SettingsGroup.Automation -> listOf(
            "tool calling", "confirmations", "voice confirmations", "permissions",
            "sms", "phone", "calendar", "contacts", "tools"
        )
        SettingsGroup.UiAutomation -> listOf(
            "accessibility", "ui control", "service"
        )
        SettingsGroup.DevicePermissions -> listOf(
            "sms", "phone", "calendar", "contacts", "voice recorder", "location",
            "microphone", "notifications", "permissions"
        )
        SettingsGroup.Mcp -> listOf("mcp", "server", "remote tools", "token")
        SettingsGroup.CloudProviders -> listOf("litellm", "providers", "cloud", "gateway")
        SettingsGroup.ChatAttachments -> listOf(
            "ocr", "image", "file", "compress", "cache", "attachment"
        )
        SettingsGroup.Safety -> listOf(
            "link", "links", "safety", "warning", "external link", "ai link", "open link", "http", "https", "url", "warn"
        )
        SettingsGroup.Developer -> listOf(
            "developer", "logs", "diagnostics", "export logs"
        )
        SettingsGroup.About -> listOf("version", "privacy", "offline", "about")
    }

@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                "Search settings",
                color = MaterialTheme.ledger.deskInkFaint
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.ledger.lampDeep
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.ledger.lampAmber,
            unfocusedBorderColor = MaterialTheme.ledger.deskHairline,
            focusedTextColor = MaterialTheme.ledger.deskPaper,
            unfocusedTextColor = MaterialTheme.ledger.deskPaper,
            cursorColor = MaterialTheme.ledger.lampAmber
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsQuickActions(
    onClearCache: () -> Unit,
    onExportMemory: () -> Unit,
    onImportMemory: () -> Unit,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Quick actions",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionChip(
                    icon = Icons.Filled.Cached,
                    label = "Clear Cache",
                    onClick = onClearCache
                )
                QuickActionChip(
                    icon = Icons.Filled.IosShare,
                    label = "Export Memory",
                    onClick = onExportMemory
                )
                QuickActionChip(
                    icon = Icons.Filled.FileUpload,
                    label = "Import Memory",
                    onClick = onImportMemory
                )
                QuickActionChip(
                    icon = Icons.Filled.SystemUpdate,
                    label = "Check for Updates",
                    onClick = onCheckUpdates
                )
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.ledger.lampDeep,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.ledger.deskPaper
            )
        )
    }
}

@Composable
internal fun SettingsAccordionHeader(
    group: SettingsGroup,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    reduceMotion: Boolean = false
) {
    val duration = if (reduceMotion) 0 else SETTINGS_ACCORDION_DURATION_MS
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
        label = "settingsChevron"
    )
    val state = if (expanded) "Expanded" else "Collapsed"

    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    stateDescription = state
                }
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.ledger.lampDeep,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank() && !expanded) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskInkFaint
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) {
                    "Collapse ${group.title}"
                } else {
                    "Expand ${group.title}"
                },
                tint = MaterialTheme.ledger.deskInkFaint,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(chevron)
            )
        }
    }
}

/**
 * Places an accordion in a [LazyListScope]. Only one group should be expanded
 * at a time; the body uses Material expand/collapse and is composed only while
 * open (lazy for heavy sections such as Speech Recognition and Automation).
 *
 * While a group is expanded its header is sticky so the category stays on
 * screen as the user scrolls through a long body.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.settingsAccordionItem(
    group: SettingsGroup,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    visible: Boolean,
    subtitle: String? = null,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    if (!visible) return
    val duration = if (reduceMotion) 0 else SETTINGS_ACCORDION_DURATION_MS
    val spec = tween<Float>(durationMillis = duration, easing = FastOutSlowInEasing)
    val enter = fadeIn(animationSpec = spec) + expandVertically(
        animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
        expandFrom = Alignment.Top
    )
    val exit = fadeOut(animationSpec = tween(durationMillis = (duration * 0.7f).toInt(), easing = FastOutSlowInEasing)) +
        shrinkVertically(
            animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        )

    if (expanded) {
        stickyHeader(key = "settings-head-${group.name}") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.ledger.deskWalnut.copy(alpha = 0.97f))
                    .padding(bottom = 8.dp)
            ) {
                SettingsAccordionHeader(
                    group = group,
                    icon = icon,
                    expanded = true,
                    onToggle = onToggle,
                    subtitle = subtitle,
                    reduceMotion = reduceMotion
                )
            }
        }
        item(key = "settings-body-${group.name}") {
            AnimatedVisibility(
                visible = true,
                enter = enter,
                exit = exit
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }
    } else {
        item(key = "settings-${group.name}") {
            SettingsAccordionHeader(
                group = group,
                icon = icon,
                expanded = false,
                onToggle = onToggle,
                subtitle = subtitle,
                reduceMotion = reduceMotion
            )
        }
    }
}
