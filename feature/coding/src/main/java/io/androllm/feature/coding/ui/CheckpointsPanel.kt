package io.androllm.feature.coding.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.task.CheckpointRef
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Checkpoint list + creator. Each row shows the name, time, file count and
 * has Restore / Delete buttons. Tapping Create with a name snapshots the
 * current workspace contents.
 */
@Composable
fun CheckpointsPanel(
    checkpoints: List<CheckpointRef>,
    onClose: () -> Unit,
    onCreate: (name: String) -> Unit,
    onRestore: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    val sdf = remember { SimpleDateFormat("MMM d • HH:mm", Locale.US) }

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
                    Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "CHECKPOINTS",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampDeep,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        "Snapshot the workspace, restore from any point in time.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = MaterialTheme.ledger.deskInk)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Checkpoint name (optional)", color = MaterialTheme.ledger.deskInkFaint, fontSize = 12.sp) }
                )
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.ledger.lampAmber,
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onCreate(name) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Camera, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Snapshot", fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (checkpoints.isEmpty()) {
                Text(
                    "No checkpoints yet. Take a snapshot before risky changes so you can roll back.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                checkpoints.forEach { cp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                cp.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.ledger.deskPaper,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                            Text(
                                "${sdf.format(Date(cp.createdAtMs))} • ${cp.fileCount} files",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                            )
                        }
                        TextButton(onClick = { onRestore(cp.id) }) {
                            Icon(Icons.Filled.Restore, contentDescription = "Restore", tint = MaterialTheme.ledger.lampDeep, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Restore", color = MaterialTheme.ledger.lampDeep, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        IconButton(onClick = { onDelete(cp.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.ledger.deskInkFaint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
