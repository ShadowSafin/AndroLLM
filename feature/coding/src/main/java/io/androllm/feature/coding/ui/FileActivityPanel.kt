package io.androllm.feature.coding.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.task.FileChangeRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recent file activity feed. Each row shows a kind-coloured icon and the
 * workspace-relative path. Newest first.
 */
@Composable
fun FileActivityPanel(
    activity: List<FileChangeRecord>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "FILE ACTIVITY",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampDeep,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        "Most recent changes in this session.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.ledger.deskInk)
                }
            }

            Spacer(Modifier.height(6.dp))
            if (activity.isEmpty()) {
                Text(
                    "No file changes yet — ask the agent to create or edit a file.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                activity.take(30).forEach { rec ->
                    val (icon, label, color) = when (rec.kind) {
                        "create" -> Triple(Icons.Filled.Create, "Created", Color(0xFF34C759))
                        "edit" -> Triple(Icons.Filled.Edit, "Edited", Color(0xFFF59E0B))
                        "delete" -> Triple(Icons.Filled.Close, "Deleted", Color(0xFFEF4444))
                        else -> Triple(Icons.Filled.Description, "Touched", MaterialTheme.ledger.deskInk)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            rec.path,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.ledger.deskPaper,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Text(
                            sdf.format(Date(rec.timestampMs)),
                            fontSize = 10.sp,
                            color = MaterialTheme.ledger.deskInkFaint
                        )
                    }
                }
            }
        }
    }
}
