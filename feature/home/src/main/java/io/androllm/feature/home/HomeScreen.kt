package io.androllm.feature.home

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.common.UiState
import io.androllm.core.common.getOrElse
import io.androllm.core.models.Conversation
import io.androllm.core.navigation.Routes
import io.androllm.core.ui.components.GradientBackground
import io.androllm.core.ui.components.SectionCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.brandPrimary
import io.androllm.feature.home.R

/**
 * Home screen: model status, quick actions and recent chats.
 */
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        GradientBackground(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ModelStatusCard(
                        isModelLoaded = (uiState as? UiState.Success)?.data?.isModelLoaded == true,
                        onManageModels = { navController.navigate(Routes.MODELS) }
                    )
                }

                item {
                    QuickActions(
                        onNewChat = { navController.navigate(Routes.CHAT) },
                        onBrowseModels = { navController.navigate(Routes.MODELS) },
                        onSettings = { navController.navigate(Routes.SETTINGS) }
                    )
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.recent_chats),
                        trailing = {
                            IconButton(onClick = {}) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null)
                            }
                        }
                    )
                }

                val conversations = (uiState as? UiState.Success)?.data?.recentConversations ?: emptyList()
                if (conversations.isEmpty()) {
                    item {
                        SectionCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Chat,
                                    contentDescription = null,
                                    tint = brandPrimary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.no_recent_chats),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.start_new_conversation),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            onClick = { navController.navigate(Routes.chatDetail(conversation.id)) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card showing the current model load status.
 */
@Composable
private fun ModelStatusCard(
    isModelLoaded: Boolean,
    onManageModels: () -> Unit
) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.model_status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(
                            if (isModelLoaded) R.string.status_model_loaded else R.string.status_no_model
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.manage_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    tint = if (isModelLoaded) brandPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Quick action buttons for primary navigation.
 */
@Composable
private fun QuickActions(
    onNewChat: () -> Unit,
    onBrowseModels: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onNewChat,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = brandPrimary)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.action_new_chat),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        FilledTonalButton(
            onClick = onBrowseModels,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = stringResource(R.string.action_browse_models))
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onSettings,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.action_settings),
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/**
 * A single recent conversation row.
 */
@Composable
private fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Chat,
                contentDescription = null,
                tint = brandPrimary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                conversation.lastMessagePreview?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = conversation.updatedAt.formatRelative(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Formats a timestamp for display.
 */
private fun Long.formatRelative(): String = io.androllm.core.common.runCatching {
    val diff = System.currentTimeMillis() - this
    when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
        diff < 604_800_000 -> "${diff / 86_400_000} days ago"
        else -> "Old"
    }
}.getOrElse { "Old" }
