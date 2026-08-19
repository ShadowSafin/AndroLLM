package io.androllm.feature.chat.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskWalnutDeep
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.feature.chat.ToolInvocationStatus
import io.androllm.feature.chat.ToolInvocationUi
import io.androllm.core.ui.theme.ledger

private val SuccessGreen = Color(0xFF52C41A)

/**
 * Column of live tool-invocation cards for the current turn. Each card shows
 * the tool name + status while it runs and is expandable to inspect the exact
 * arguments sent and the result the model received.
 */
@Composable
fun ToolInvocationCards(
    toolEvents: List<ToolInvocationUi>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        toolEvents.forEach { event ->
            ToolInvocationCard(event = event)
        }
    }
}

/** Emoji glyph per tool family, so a card is scannable at a glance. */
private fun toolEmoji(name: String): String = when {
    name.contains("search") || name.contains("web") -> "🔍"
    name.contains("app") || name.contains("launch") || name.contains("open") -> "📱"
    name.contains("location") || name.contains("maps") || name.contains("map") -> "📍"
    name.contains("pdf") || name.contains("file") || name.contains("download") || name.contains("export") -> "📄"
    name.contains("clipboard") -> "📋"
    name.contains("flashlight") || name.contains("torch") -> "🔦"
    name.contains("battery") -> "🔋"
    name.contains("wifi") -> "📶"
    name.contains("bluetooth") -> "🅱️"
    name.contains("volume") || name.contains("music") || name.contains("media") -> "🔊"
    name.contains("sms") || name.contains("message") -> "💬"
    name.contains("phone") || name.contains("call") -> "📞"
    name.contains("email") -> "📧"
    name.contains("calendar") || name.contains("alarm") || name.contains("reminder") -> "⏰"
    name.contains("note") || name.contains("memory") -> "🗒️"
    name.contains("camera") || name.contains("photo") || name.contains("gallery") -> "📷"
    name.contains("screen") || name.contains("screenshot") -> "🖥️"
    name.contains("contact") -> "👤"
    name.contains("device") || name.contains("battery") -> "📟"
    name.contains("ui_") -> "🖱️"
    else -> "🤖"
}

/** Human-readable tool title: snake_case → "Set Alarm". */
private fun toolTitle(name: String): String {
    val words = name.split('_').filter { it.isNotBlank() }
    if (words.isEmpty()) return name
    return words.joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

@Composable
private fun ToolInvocationCard(
    event: ToolInvocationUi,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (event.status) {
        ToolInvocationStatus.RUNNING -> MaterialTheme.ledger.lampAmber
        ToolInvocationStatus.SUCCESS -> SuccessGreen
        ToolInvocationStatus.FAILED -> MaterialTheme.ledger.lampDeep
        ToolInvocationStatus.DECLINED -> MaterialTheme.ledger.deskInkFaint
    }
    val statusLabel = when (event.status) {
        ToolInvocationStatus.RUNNING -> "Running…"
        ToolInvocationStatus.SUCCESS -> "Done"
        ToolInvocationStatus.FAILED -> "Failed"
        ToolInvocationStatus.DECLINED -> "Declined"
    }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "tool_chevron")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.ledger.deskWalnutRaised,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = toolEmoji(event.name), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = toolTitle(event.name),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.ledger.deskPaper
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (event.status == ToolInvocationStatus.RUNNING) {
                    Text(text = "●", color = statusColor, fontSize = 10.sp)
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.ledger.deskInkFaint,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (expanded) {
                if (event.arguments.isNotBlank()) {
                    DetailSection(label = "Arguments", value = event.arguments)
                }
                when (event.status) {
                    ToolInvocationStatus.SUCCESS ->
                        if (event.summary.isNotBlank()) DetailSection(label = "Result", value = event.summary)

                    ToolInvocationStatus.FAILED ->
                        if (event.error.isNotBlank()) DetailSection(label = "Error", value = event.error)

                    ToolInvocationStatus.DECLINED ->
                        DetailSection(label = "Note", value = "You declined this action.")

                    ToolInvocationStatus.RUNNING -> Unit
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.ledger.lampGlow,
                letterSpacing = 0.8.sp
            )
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.ledger.deskWalnutDeep
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.ledger.deskInk,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}
