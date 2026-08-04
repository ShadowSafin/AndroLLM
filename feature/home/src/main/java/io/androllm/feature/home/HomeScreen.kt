package io.androllm.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.androllm.core.common.getOrElse
import io.androllm.core.models.Conversation
import io.androllm.core.navigation.Routes
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
import io.androllm.feature.home.R

/**
 * Cloud Intelligence Home Experience.
 * Cinematic welcome header, floating cloud islands, suggested prompts, and recent chats.
 */
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    CloudAtmosphericBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CloudBugdroidLogo(size = 38.dp, showMoon = false)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.home_title),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CloudWhite
                                    )
                                )
                                Text(
                                    text = "Intelligence Above The Clouds",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = io.androllm.core.ui.theme.SunsetCloudPeach,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.action_settings),
                                tint = MoonSilver
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
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
                // 1. Cinematic Model Status Island
                item {
                    ModelStatusIsland(
                        isModelLoaded = (uiState as? UiState.Success)?.data?.isModelLoaded == true,
                        onManageModels = { navController.navigate(Routes.MODELS) }
                    )
                }

                // 2. Floating Quick Actions Capsules
                item {
                    QuickActionsRow(
                        onNewChat = { navController.navigate(Routes.CHAT) },
                        onBrowseModels = { navController.navigate(Routes.MODELS) },
                        onSettings = { navController.navigate(Routes.SETTINGS) }
                    )
                }

                // 3. Suggested Prompts Cloud Grid
                item {
                    SuggestedPromptsSection(
                        onPromptSelected = { prompt ->
                            navController.navigate(Routes.CHAT)
                        }
                    )
                }

                // 4. Recent Conversations Header
                item {
                    SectionHeader(
                        title = stringResource(R.string.recent_chats),
                        subtitle = "Your private on-device conversations",
                        trailing = {
                            CloudChip(
                                text = "Offline & Private",
                                accentColor = SoftCyan
                            )
                        }
                    )
                }

                // 5. Recent Conversations List
                val conversations = (uiState as? UiState.Success)?.data?.recentConversations ?: emptyList()
                if (conversations.isEmpty()) {
                    item {
                        EmptyChatsIsland(
                            onStartChat = { navController.navigate(Routes.CHAT) }
                        )
                    }
                } else {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationGlassCard(
                            conversation = conversation,
                            onClick = { navController.navigate(Routes.chatDetail(conversation.id)) }
                        )
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
 * Model status card styled as a floating cloud island.
 */
@Composable
private fun ModelStatusIsland(
    isModelLoaded: Boolean,
    onManageModels: () -> Unit
) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onManageModels
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CloudChip(
                        text = if (isModelLoaded) "Vulkan Engine Ready" else "No Model Active",
                        accentColor = if (isModelLoaded) SoftCyan else AuroraCyan,
                        icon = Icons.Filled.Memory
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (isModelLoaded) "On-Device Inference Active" else "Select a GGUF Model to Begin",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = CloudWhite
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isModelLoaded) "Zero cloud dependency • Local RAM" else "Tap to explore model catalog & download",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MoonSilver.copy(alpha = 0.75f)
                    )
                )
            }
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = if (isModelLoaded) SkyBlue else MoonSilver.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

/**
 * Quick action capsules row.
 */
@Composable
private fun QuickActionsRow(
    onNewChat: () -> Unit,
    onBrowseModels: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CloudCapsuleButton(
            text = stringResource(R.string.action_new_chat),
            onClick = onNewChat,
            icon = Icons.Filled.Add,
            modifier = Modifier.weight(1.2f)
        )
        CloudCapsuleButton(
            text = stringResource(R.string.action_browse_models),
            onClick = onBrowseModels,
            gradient = Brush.horizontalGradient(listOf(CloudWhite.copy(alpha = 0.15f), SkyBlue.copy(alpha = 0.25f))),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Suggested Prompts Cloud Grid
 */
@Composable
private fun SuggestedPromptsSection(
    onPromptSelected: (String) -> Unit
) {
    val prompts = listOf(
        PromptItem("Explain Quantum Computing", "In simple terms", Icons.Filled.Lightbulb),
        PromptItem("Write a Kotlin Coroutine", "Clean async code", Icons.Filled.Code),
        PromptItem("Brainstorm App Ideas", "Creative AI session", Icons.Filled.AutoAwesome)
    )

    Column {
        Text(
            text = "Suggested Prompts",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = CloudWhite
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 8.dp)
        ) {
            items(prompts) { item ->
                CloudGlassCard(
                    modifier = Modifier.width(190.dp),
                    onClick = { onPromptSelected(item.title) },
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = CloudWhite
                            ),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MoonSilver.copy(alpha = 0.65f)
                            )
                        )
                    }
                }
            }
        }
    }
}

private data class PromptItem(val title: String, val subtitle: String, val icon: ImageVector)

/**
 * Empty chats island.
 */
@Composable
private fun EmptyChatsIsland(onStartChat: () -> Unit) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onStartChat,
        contentPadding = PaddingValues(28.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.Chat,
                contentDescription = null,
                tint = SkyBlue,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.no_recent_chats),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = CloudWhite
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.start_new_conversation),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MoonSilver.copy(alpha = 0.75f)
                )
            )
        }
    }
}

/**
 * Recent conversation item card.
 */
@Composable
private fun ConversationGlassCard(
    conversation: Conversation,
    onClick: () -> Unit
) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SkyBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Chat,
                    contentDescription = null,
                    tint = SkyBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = CloudWhite
                    ),
                    maxLines = 1
                )
                conversation.lastMessagePreview?.let { preview ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MoonSilver.copy(alpha = 0.7f)
                        ),
                        maxLines = 1
                    )
                }
            }

            Text(
                text = conversation.updatedAt.formatRelative(),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MoonSilver.copy(alpha = 0.5f)
                )
            )
        }
    }
}

private fun Long.formatRelative(): String = io.androllm.core.common.runCatching {
    val diff = System.currentTimeMillis() - this
    when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> "Old"
    }
}.getOrElse { "Old" }
