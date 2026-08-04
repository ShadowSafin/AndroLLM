package io.androllm.feature.chat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.models.MessageRole
import io.androllm.core.ui.components.CloudBugdroidLogo
import io.androllm.core.ui.theme.AzureBlue
import io.androllm.core.ui.theme.CloudCapsuleShape
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudGlassSurface
import io.androllm.core.ui.theme.CloudIslandShape
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.ElectricBlue
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SoftCyan
import io.androllm.feature.chat.ChatMessage
import io.androllm.feature.chat.export.ConversationSharer
import io.androllm.feature.chat.ui.markdown.MarkdownRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud Intelligence Message Bubble.
 * Floating cloud island styling for Assistant, Azure cloud capsule for User,
 * streaming animations, and quick action toolbars.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    markdownEnabled: Boolean = true,
    codeWrapping: Boolean = false,
    isBookmarked: Boolean = false,
    onRegenerate: () -> Unit = {},
    onEditPrompt: () -> Unit = {},
    onDelete: () -> Unit = {},
    onBookmarkToggle: () -> Unit = {}
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM
    val isAssistant = message.role == MessageRole.ASSISTANT

    val formattedTime = remember(message.timestamp) {
        if (message.timestamp > 0) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
        } else ""
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(initialOffsetY = { 24 })
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(0.94f),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                if (!isUser && !isSystem) {
                    // Assistant Cloud Bugdroid Avatar
                    CloudBugdroidLogo(size = 34.dp, showMoon = false)
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column {
                    // Message Card Surface
                    Surface(
                        shape = when {
                            isSystem -> CloudCapsuleShape
                            isUser -> CloudCapsuleShape
                            else -> CloudIslandShape
                        },
                        color = when {
                            isSystem -> CloudGlassSurface
                            isUser -> ElectricBlue
                            else -> CloudGlassSurface
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isUser) AzureBlue.copy(alpha = 0.5f) else CloudGlassBorder
                        ),
                        shadowElevation = if (isUser) 4.dp else 8.dp,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isUser) {
                                        Brush.horizontalGradient(listOf(ElectricBlue, AzureBlue))
                                    } else {
                                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent))
                                    }
                                )
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            Column {
                                val contentToRender = if (isStreaming) "${message.content}▋" else message.content

                                if (markdownEnabled && !isUser) {
                                    MarkdownRenderer(
                                        markdownText = contentToRender,
                                        textColor = CloudWhite,
                                        codeWrapping = codeWrapping
                                    )
                                } else {
                                    Text(
                                        text = contentToRender,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            lineHeight = 22.sp,
                                            color = CloudWhite
                                        )
                                    )
                                }

                                // Timestamp & Bookmark Indicators
                                Row(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isBookmarked) {
                                        Icon(
                                            imageVector = Icons.Default.Bookmark,
                                            contentDescription = "Bookmarked",
                                            tint = SoftCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    if (formattedTime.isNotEmpty()) {
                                        Text(
                                            text = formattedTime,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = if (isUser) CloudWhite.copy(alpha = 0.75f) else MoonSilver.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Floating Quick Actions Bar for Assistant Messages
                    if (isAssistant && !isStreaming) {
                        Row(
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Message", message.content))
                                    Toast.makeText(context, "Copied text", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = MoonSilver.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Regenerate",
                                    tint = MoonSilver.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = { ConversationSharer.shareSingleMessage(context, message.content) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = MoonSilver.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    // Full Context Dropdown Menu
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Text") },
                            onClick = {
                                showMenu = false
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Message", message.content))
                                Toast.makeText(context, "Copied text", Toast.LENGTH_SHORT).show()
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                        )

                        if (isUser) {
                            DropdownMenuItem(
                                text = { Text("Edit Prompt") },
                                onClick = {
                                    showMenu = false
                                    onEditPrompt()
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                        } else if (isAssistant) {
                            DropdownMenuItem(
                                text = { Text("Regenerate") },
                                onClick = {
                                    showMenu = false
                                    onRegenerate()
                                },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                            )
                        }

                        DropdownMenuItem(
                            text = { Text(if (isBookmarked) "Remove Bookmark" else "Bookmark Message") },
                            onClick = {
                                showMenu = false
                                onBookmarkToggle()
                            },
                            leadingIcon = { Icon(if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = null) }
                        )

                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                showMenu = false
                                ConversationSharer.shareSingleMessage(context, message.content)
                            },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                        )

                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    }
}
