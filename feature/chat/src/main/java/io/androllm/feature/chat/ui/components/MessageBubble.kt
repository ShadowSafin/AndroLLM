package io.androllm.feature.chat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.models.MessageOrigin
import io.androllm.core.models.MessageRole
import io.androllm.core.ui.components.LampDot
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskSlipShape
import io.androllm.core.ui.theme.DeskWalnut
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.LampAmber
import io.androllm.feature.chat.ChatMessage
import io.androllm.feature.chat.export.ConversationSharer
import io.androllm.feature.chat.ui.markdown.MarkdownRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.androllm.core.ui.theme.ledger

/**
 * A letter on the desk — one turn in the correspondence.
 *
 * The assistant's words are the page itself: ink on parchment, entered with a
 * small lit terracotta seal and separated from the next letter by a cream
 * hairline rule. Your own words are a white slip, set flush to the right edge
 * with a ruled margin and a single terracotta edge — the ink you pressed to
 * paper. No bubbles, no glass: the conversation reads as correspondence kept
 * in daylight.
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
        enter = fadeIn() + slideInVertically(initialOffsetY = { 16 })
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                if (!isUser && !isSystem) {
                    // The lamp dot that entered the letter.
                    Box(
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        LampDot(size = 8.dp, lit = true)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // The letter header: who wrote, at what hour.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = when {
                                isUser -> "YOU"
                                isSystem -> "NOTE"
                                else -> "AI"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.8.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isUser) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
                            )
                        )
                        if (formattedTime.isNotEmpty()) {
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.2.sp,
                                    color = MaterialTheme.ledger.deskInkFaint
                                )
                            )
                        }
                        if (isBookmarked) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = "Bookmarked",
                                tint = MaterialTheme.ledger.lampDeep.copy(alpha = 0.9f),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        if (isUser && message.origin == MessageOrigin.VOICE) {
                            // 🎤 voice origin — the user spoke this prompt after
                            // the wake word fired. Visually identical to a
                            // typed message, just a small mic chip in the
                            // header.
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice",
                                tint = MaterialTheme.ledger.lampDeep.copy(alpha = 0.9f),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    val contentToRender = if (isStreaming) "${message.content}▋" else message.content

                    // The slip: walnut for your words, the bare page for the lamp's.
                    Box(
                        modifier = Modifier
                            .then(
                                if (isUser) {
                                    Modifier
                                        .fillMaxWidth(0.86f)
                                        .align(Alignment.End)
                                        .clip(DeskSlipShape)
                                        .background(MaterialTheme.ledger.deskWalnutRaised)
                                        .border(1.dp, MaterialTheme.ledger.deskHairline, DeskSlipShape)
                                        .padding(horizontal = 18.dp, vertical = 14.dp)
                                } else {
                                    Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.Start)
                                        .padding(end = 16.dp)
                                }
                            )
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showMenu = true }
                            )
                    ) {
                        Column {
                            if (markdownEnabled && !isUser) {
                                MarkdownRenderer(
                                    markdownText = contentToRender,
                                    textColor = MaterialTheme.ledger.deskPaper,
                                    codeWrapping = codeWrapping
                                )
                            } else {
                                Text(
                                    text = contentToRender,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.ledger.deskPaper
                                    )
                                )
                            }
                        }
                    }

                    // Quick actions, set in ink — for the lamp's letters only.
                    if (isAssistant && !isStreaming) {
                        Row(
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            InkIconButton(Icons.Default.ContentCopy, "Copy", tint = MaterialTheme.ledger.deskInkFaint) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Message", message.content))
                                Toast.makeText(context, "Copied text", Toast.LENGTH_SHORT).show()
                            }
                            InkIconButton(Icons.Default.Refresh, "Regenerate", tint = MaterialTheme.ledger.deskInkFaint) { onRegenerate() }
                            InkIconButton(Icons.Default.Share, "Share", tint = MaterialTheme.ledger.deskInkFaint) {
                                ConversationSharer.shareSingleMessage(context, message.content)
                            }
                        }
                    }
                }
            }

            // The ruled rule that closes the letter.
            if (!isStreaming) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(Color.Transparent, MaterialTheme.ledger.deskHairline, Color.Transparent)
                            )
                        )
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    // Long-press ledger of actions.
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
            text = { Text("Delete", color = MaterialTheme.ledger.emberRed) },
            onClick = {
                showMenu = false
                onDelete()
            },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.ledger.emberRed) }
        )
    }
}

@Composable
private fun InkIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}
