package io.androllm.feature.chat.ui.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.models.Conversation

/**
 * Sidebar Drawer for conversation management, quick search, system storage info, and settings shortcuts.
 */
@Composable
fun ConversationDrawerContent(
    activeConversations: List<Conversation>,
    pinnedConversations: List<Conversation>,
    selectedConversationId: String,
    currentModelName: String?,
    ramUsageText: String,
    storageUsageText: String,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onPinToggle: (Conversation) -> Unit,
    onRenameChat: (Conversation) -> Unit,
    onDuplicateChat: (Conversation) -> Unit,
    onDeleteChat: (Conversation) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(
        modifier = modifier.width(320.dp),
        drawerContainerColor = io.androllm.core.ui.theme.DeskWalnut
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            // App Header — the wordmark on the walnut.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    io.androllm.core.ui.components.CloudBugdroidLogo(size = 28.dp, showMoon = false)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "AndroLLM",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = io.androllm.core.ui.theme.DeskPaper
                            )
                        )
                        Text(
                            text = "PRIVATE AI · ON-DEVICE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = io.androllm.core.ui.theme.DeskInk
                            )
                        )
                    }
                }

                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search chats", tint = io.androllm.core.ui.theme.DeskPaper)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // + New Chat Button — one amber capsule.
            io.androllm.core.ui.components.CloudCapsuleButton(
                text = "New Chat",
                onClick = onNewChat,
                icon = Icons.Default.Add,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Conversation Lists
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (pinnedConversations.isNotEmpty()) {
                    item {
                        Text(
                            text = "PINNED CHATS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = io.androllm.core.ui.theme.LampDeep,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    items(pinnedConversations, key = { "pinned_${it.id}" }) { conv ->
                        ConversationDrawerItem(
                            conversation = conv,
                            isSelected = conv.id == selectedConversationId,
                            onSelect = { onSelectConversation(conv.id) },
                            onPinToggle = { onPinToggle(conv) },
                            onRename = { onRenameChat(conv) },
                            onDuplicate = { onDuplicateChat(conv) },
                            onDelete = { onDeleteChat(conv) }
                        )
                    }

                    item {
                        HorizontalDivider(color = io.androllm.core.ui.theme.DeskHairline, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }

                item {
                    Text(
                        text = "RECENT CHATS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = io.androllm.core.ui.theme.DeskInk,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (activeConversations.isEmpty()) {
                    item {
                        Text(
                            text = "No recent chats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(activeConversations, key = { "recent_${it.id}" }) { conv ->
                        ConversationDrawerItem(
                            conversation = conv,
                            isSelected = conv.id == selectedConversationId,
                            onSelect = { onSelectConversation(conv.id) },
                            onPinToggle = { onPinToggle(conv) },
                            onRename = { onRenameChat(conv) },
                            onDuplicate = { onDuplicateChat(conv) },
                            onDelete = { onDeleteChat(conv) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Footer System Info & Settings
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The loaded model, as a ruled journal entry.
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = io.androllm.core.ui.theme.DeskWalnutRaised,
                    border = androidx.compose.foundation.BorderStroke(1.dp, io.androllm.core.ui.theme.DeskHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        io.androllm.core.ui.components.LampDot(size = 8.dp, lit = currentModelName != null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = currentModelName ?: "No model loaded",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = io.androllm.core.ui.theme.DeskPaper,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "RAM $ramUsageText · KEEP $storageUsageText",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = io.androllm.core.ui.theme.DeskInk
                                )
                            )
                        }
                    }
                }

                // Settings Navigation Shortcut
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenSettings() }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = io.androllm.core.ui.theme.DeskPaper
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = io.androllm.core.ui.theme.DeskPaper,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationDrawerItem(
    conversation: Conversation,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPinToggle: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    NavigationDrawerItem(
        label = {
            Text(
                text = conversation.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        },
        selected = isSelected,
        onClick = onSelect,
        icon = {
            Icon(
                imageVector = if (conversation.isPinned) Icons.Default.PushPin else Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = if (conversation.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        },
        badge = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                        onClick = {
                            menuExpanded = false
                            onPinToggle()
                        },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        },
                        leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        },
        shape = RoundedCornerShape(8.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            unselectedContainerColor = Color.Transparent
        ),
        modifier = Modifier.height(44.dp)
    )
}
