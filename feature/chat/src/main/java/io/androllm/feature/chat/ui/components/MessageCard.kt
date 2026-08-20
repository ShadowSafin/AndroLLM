package io.androllm.feature.chat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.attachments.model.ChatAttachment
import io.androllm.core.models.MessageRole
import io.androllm.core.ui.components.LampDot
import io.androllm.feature.chat.ChatAttachmentJson
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskHairlineSoft
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskWalnut
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.feature.chat.ChatMessage
import io.androllm.feature.chat.export.ConversationSharer
import io.androllm.feature.chat.ui.markdown.MarkdownRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.androllm.core.ui.theme.ledger

/**
 * An adaptive message card — the living page of the conversation.
 *
 * Unlike the old letter-slip, each turn is a soft card: dynamic corner radii
 * (tail on the speaker's edge), a light glass tint over the parchment canvas,
 * a quiet elevation, and a spring entrance that finishes in ~220ms. The
 * assistant's card is the full page; your words are a warmer slip set to the
 * right at a comfortable reading width.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageCard(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    showBadge: Boolean = false,
    markdownEnabled: Boolean = true,
    codeWrapping: Boolean = false,
    cloudMode: Boolean = false,
    /**
     * True when the active model supports attachments (cloud only). When an
     * older conversation carries attachments but the user is on a local
     * model, the cards stay visible but non-interactive, with a subtle
     * cloud-only notice.
     */
    attachmentsEnabled: Boolean = true,
    messageAnimations: Boolean = true,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onRegenerate: () -> Unit = {},
    onEditPrompt: () -> Unit = {},
    onDelete: () -> Unit = {},
    onBookmarkToggle: () -> Unit = {},
    onStop: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val context = LocalContext.current
    val isUser = message.role == MessageRole.USER
    val isAssistant = message.role == MessageRole.ASSISTANT

    // Files attached to this message ("" = none). Rendered as attachment
    // cards under the bubble; tapping one opens the original file.
    val attachments = remember(message.attachmentsJson) {
        ChatAttachmentJson.decodeFromString(message.attachmentsJson)
    }

    val formattedTime = remember(message.timestamp) {
        if (message.timestamp > 0) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
        } else ""
    }

    // ── Spring entrance: fade + lift + settle, once per message id ────────────
    val entrance = remember(message.id) { Animatable(0f) }
    LaunchedEffect(message.id, isStreaming) {
        if (messageAnimations && !isStreaming) {
            entrance.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            entrance.snapTo(1f)
        }
    }
    val liftPx = with(LocalDensity.current) { 22.dp.toPx() }

    // ── Collapsible long responses (assistant only, never while streaming) ───
    var expanded by remember(message.id) { mutableStateOf(false) }
    val isCollapsible = isAssistant && !isStreaming && message.content.length > 900
    val displayContent = if (isCollapsible && !expanded) {
        truncateAtWord(message.content, 640)
    } else {
        message.content
    }

    // ── Selection / favourite tap handling ─────────────────────────────────────
    val cardShape = if (isUser) {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp)
    } else {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 8.dp)
    }

    val selectionTint = if (selected) MaterialTheme.ledger.lampAmber.copy(alpha = 0.12f) else Color.Transparent

    val bgColor = if (isUser) {
        // Your words sit on the terracotta wash — the lamp's own tint at a
        // quiet alpha, always in the ledger family so the page never splits
        // into a second (dynamic-color) theme.
        MaterialTheme.ledger.lampAmber.copy(alpha = 0.14f)
    } else {
        // Glass tint: the parchment shows through the card.
        MaterialTheme.ledger.deskWalnut.copy(alpha = 0.66f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                Box(modifier = Modifier.padding(top = 10.dp)) { LampDot(size = 7.dp, lit = true) }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (isUser) Modifier.fillMaxWidth(0.86f) else Modifier)
            ) {
                // ── Card header: who wrote, when, and on which backend ─────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 5.dp)
                ) {
                    Text(
                        text = when {
                            isUser -> "YOU"
                            message.role == MessageRole.SYSTEM -> "NOTE"
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
                                letterSpacing = 1.1.sp,
                                color = MaterialTheme.ledger.deskInkFaint
                            )
                        )
                    }
                    if (isAssistant && showBadge) {
                        BackendBadge(cloudMode = cloudMode)
                    }
                    if (message.isBookmarked) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = "Bookmarked",
                            tint = MaterialTheme.ledger.lampDeep.copy(alpha = 0.9f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Surface(
                    shape = cardShape,
                    color = bgColor,
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskHairline
                    ),
                    shadowElevation = if (selected) 4.dp else 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = entrance.value
                            val s = 0.97f + 0.03f * entrance.value
                            scaleX = s
                            scaleY = s
                            translationY = (1f - entrance.value) * liftPx
                        }
                        .combinedClickable(
                            onClick = {
                                if (selectionActive) onClick() else Unit
                            },
                            onLongClick = {
                                if (selectionActive) onClick() else onLongPress()
                            },
                            onDoubleClick = { onBookmarkToggle() }
                        )
                        .clip(cardShape)
                ) {
                    Box(modifier = Modifier.background(selectionTint)) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (markdownEnabled && !isUser) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        MarkdownRenderer(
                                            markdownText = displayContent,
                                            textColor = MaterialTheme.ledger.deskPaper,
                                            codeWrapping = codeWrapping
                                        )
                                    }
                                    if (isStreaming) BlinkingCursor()
                                }
                            } else {
                                Text(
                                    text = if (isStreaming) displayContent else message.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.ledger.deskPaper
                                    )
                                )
                            }

                            // ── Read more / show less ─────────────────────────
                            if (isCollapsible) {
                                TextButton(
                                    onClick = { expanded = !expanded },
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        text = if (expanded) "Show less" else "Read more",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = MaterialTheme.ledger.lampDeep,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }

                            // ── Attached files ───────────────────────────────
                            if (attachments.isNotEmpty() && !isStreaming) {
                                Spacer(modifier = Modifier.height(8.dp))
                                AttachmentCards(
                                    attachments = attachments,
                                    enabled = attachmentsEnabled,
                                    onOpen = { attachment ->
                                        openAttachment(context, attachment)
                                    }
                                )
                                // Old chats opened on a local model: cards stay
                                // visible but inactive, with a subtle notice.
                                if (!attachmentsEnabled) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "This conversation contains cloud-only attachments. Switch to a cloud model to use them.",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.ledger.deskInkFaint
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Action bar: assistant cards get the full set, user cards
                //    get edit/copy/delete so prompts stay editable. ─────────────
                if ((isAssistant || isUser) && !selectionActive) {
                    if (isStreaming) {
                        Row(
                            modifier = Modifier.padding(top = 5.dp, start = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            CardIconButton(Icons.Default.Stop, "Stop", MaterialTheme.ledger.lampDeep) { onStop() }
                        }
                    } else {
                        Row(
                            modifier = Modifier.padding(top = 5.dp, start = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            CardIconButton(Icons.Default.ContentCopy, "Copy", MaterialTheme.ledger.deskInkFaint) {
                                copyToClipboard(context, message.content, "Copied text")
                            }
                            if (isUser) {
                                CardIconButton(Icons.Default.Edit, "Edit prompt", MaterialTheme.ledger.deskInkFaint) { onEditPrompt() }
                            } else {
                                CardIconButton(Icons.Default.Refresh, "Regenerate", MaterialTheme.ledger.deskInkFaint) { onRegenerate() }
                                CardIconButton(
                                    if (message.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    if (message.isBookmarked) "Remove bookmark" else "Bookmark",
                                    if (message.isBookmarked) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInkFaint
                                ) { onBookmarkToggle() }
                                CardIconButton(Icons.Default.Share, "Share", MaterialTheme.ledger.deskInkFaint) {
                                    ConversationSharer.shareSingleMessage(context, message.content)
                                }
                            }
                            CardIconButton(Icons.Default.Delete, "Delete", MaterialTheme.ledger.deskInkFaint) { onDelete() }
                        }
                    }
                }
            }
        }

        if (!isStreaming) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.Transparent, MaterialTheme.ledger.deskHairlineSoft, Color.Transparent)
                        )
                    )
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/** A tiny chip labelling where the response was produced. */
@Composable
private fun BackendBadge(cloudMode: Boolean) {
    val label = if (cloudMode) "CLOUD" else "LOCAL"
    val fg = if (cloudMode) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
    val bg = if (cloudMode) MaterialTheme.ledger.lampAmber.copy(alpha = 0.14f) else MaterialTheme.ledger.deskWalnutRaised.copy(alpha = 0.8f)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
        border = BorderStroke(0.5.dp, if (cloudMode) MaterialTheme.ledger.lampAmber.copy(alpha = 0.5f) else MaterialTheme.ledger.deskHairline)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )
        )
    }
}

/** A smoothly blinking caret shown while tokens stream. */
@Composable
private fun BlinkingCursor(modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    }
    Box(
        modifier = modifier
            .padding(start = 3.dp, bottom = 3.dp)
            .size(width = 3.dp, height = 16.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.ledger.lampDeep, RoundedCornerShape(1.5.dp))
    )
}

@Composable
private fun CardIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
    }
}

private fun copyToClipboard(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/**
 * Attachment cards rendered under a message that carried files. Each card
 * shows the file icon, name, size and a processing status; tapping a ready
 * card opens the original file. Mirrors the ChatGPT attachment chip.
 */
@Composable
private fun AttachmentCards(
    attachments: List<ChatAttachment>,
    enabled: Boolean = true,
    onOpen: (ChatAttachment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        attachments.forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (enabled) MaterialTheme.ledger.deskWalnutRaised.copy(alpha = 0.8f) else MaterialTheme.ledger.deskWalnutRaised.copy(alpha = 0.45f),
                border = BorderStroke(
                    0.5.dp,
                    if (attachment.isFailed) MaterialTheme.ledger.emberRed.copy(alpha = 0.5f) else MaterialTheme.ledger.deskHairline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    // Local models: cards render but interaction is disabled.
                    .clickable(enabled = enabled && attachment.isReady) { onOpen(attachment) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // File-type glyph: paperclip for documents, image icon for photos.
                    val glyph = if (attachment.type == io.androllm.core.attachments.model.AttachmentType.IMAGE) {
                        "🖼"
                    } else {
                        "📄"
                    }
                    Text(text = glyph, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.name,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.ledger.deskPaper,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                attachment.isFailed -> "Failed to process"
                                attachment.status == io.androllm.core.attachments.model.AttachmentStatus.PROCESSING -> "Processing…"
                                else -> attachment.label.substringAfter(" · ")
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (attachment.isFailed) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskInkFaint
                            )
                        )
                    }
                    if (attachment.isReady) {
                        Text(
                            text = attachment.type.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.lampDeep,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Opens an attached file in the system viewer. Prefers the private copy via
 * FileProvider (granted automatically), falling back to the original SAF URI.
 * Never throws — failures surface as a toast so chat stays usable.
 */
private fun openAttachment(context: Context, attachment: ChatAttachment) {
    try {
        val file = java.io.File(attachment.filePath)
        val uri: Uri = if (file.exists()) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else if (attachment.sourceUri.isNotBlank()) {
            Uri.parse(attachment.sourceUri)
        } else {
            Toast.makeText(context, "File no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = attachment.mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open ${attachment.name}"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Truncates [text] to at most [max] characters, cutting at the last word. If
 * the cut lands inside a fenced region (code block / math block), the closing
 * fence is re-appended so the Markdown parser never swallows the rest of the
 * preview as one unclosed block.
 */
private fun truncateAtWord(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.take(max)
    val lastSpace = cut.lastIndexOf(' ')
    val lastNewline = cut.lastIndexOf('\n')
    val end = maxOf(lastSpace, lastNewline).takeIf { it > max / 2 } ?: max
    val truncated = cut.take(end).trimEnd()
    val codeFences = Regex("```").findAll(truncated).count()
    val mathFences = Regex("\\$\\$").findAll(truncated).count()
    val balanced = buildString {
        append(truncated)
        if (codeFences % 2 == 1) append("\n```")
        if (mathFences % 2 == 1) append("\n$$")
    }
    return balanced + " …"
}
