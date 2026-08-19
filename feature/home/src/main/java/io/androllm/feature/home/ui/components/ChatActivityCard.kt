package io.androllm.feature.home.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.models.Conversation
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.androllm.core.ui.theme.ledger

/**
 * Parchment Ledger — a letter entry in the Recent Letters ledger.
 * One terracotta seal for the correspondence; the rest stays quiet ink.
 */
@Composable
fun ChatActivityCard(
    conversation: Conversation,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedTime = rememberFormattedTime(conversation.updatedAt)

    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {                    // Letter Seal — the one terracotta mark on the row
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(MaterialTheme.ledger.lampDeep.copy(alpha = 0.45f), MaterialTheme.ledger.lampAmber.copy(alpha = 0.2f)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.lampDeep,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Letter Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.title.ifBlank { "New Conversation" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.deskPaper
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.ledger.deskInkFaint
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Model Tag — a wax seal inked on parchment
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = conversation.lastMessagePreview?.take(16) ?: "On-Device",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                            color = MaterialTheme.ledger.lampDeep
                        )
                    }

                    Text(
                        text = if (conversation.messageCount == 1) "1 message" else "${conversation.messageCount} messages",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.ledger.deskInk
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.ledger.deskInkFaint
                )
            }
        }
    }
}

@Composable
private fun rememberFormattedTime(timestamp: Long): String {
    return androidx.compose.runtime.remember(timestamp) {
        if (timestamp <= 0) "Just now"
        else {
            val diff = System.currentTimeMillis() - timestamp
            when {
                diff < 60_000L -> "Just now"
                diff < 3600_000L -> "${diff / 60_000L}m ago"
                diff < 86400_000L -> "${diff / 3600_000L}h ago"
                else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }
}
