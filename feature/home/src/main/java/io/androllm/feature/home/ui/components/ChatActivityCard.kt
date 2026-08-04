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
import io.androllm.core.ui.theme.CloudGlassBorder
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.RevolutCyberCyan
import io.androllm.core.ui.theme.RevolutNeonEmerald
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Revolut Activity Feed Style Chat Card.
 * Displays transactional AI conversation metrics, model tags, and latency indicators.
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
        ) {
            // Activity Type Icon Bubble
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(SkyBlue.copy(alpha = 0.25f), SunsetCloudPeach.copy(alpha = 0.15f)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = SunsetCloudPeach,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Conversation Info
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
                            color = CloudWhite
                        ),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MoonSilver.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Model Used Tag
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x33E2E8F0))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = conversation.modelId.ifBlank { "On-Device AI" },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RevolutCyberCyan
                        )
                    }

                    Text(
                        text = "${conversation.messageCount} messages",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MoonSilver.copy(alpha = 0.6f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = MoonSilver.copy(alpha = 0.6f)
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
