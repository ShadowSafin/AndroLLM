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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.task.CodingTaskState
import io.androllm.feature.coding.tools.PlanStepStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Banner shown above the chat when a previously-persisted task is waiting for
 * the user to resume or discard. Renders a short summary (last update time,
 * plan progress) and three actions.
 */
@Composable
fun ResumeTaskBanner(
    task: CodingTaskState,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sdf = remember { SimpleDateFormat("MMM d • HH:mm", Locale.US) }
    val pendingSteps = task.plan.count { it.status == PlanStepStatus.PENDING }
    val doneSteps = task.plan.count { it.status == PlanStepStatus.DONE }

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
                        .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "RESUME PRIOR TASK",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampDeep,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        "Saved ${sdf.format(Date(task.lastUpdatedMs))} • $doneSteps done, $pendingSteps pending" +
                            if (task.changedFiles.isNotEmpty()) " • ${task.changedFiles.size} file changes" else "",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
            }
            if (task.plan.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                task.plan.take(5).forEach { step ->
                    Text(
                        "  ${if (step.status == PlanStepStatus.DONE) "✓" else "•"} ${step.text}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.ledger.deskPaper,
                            fontWeight = if (step.status == PlanStepStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Normal
                        ),
                        maxLines = 1
                    )
                }
                if (task.plan.size > 5) {
                    Text(
                        "  … and ${task.plan.size - 5} more",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.ledger.deskHairlineSoft,
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(onClick = onDiscard)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Discard", color = MaterialTheme.ledger.deskInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.ledger.lampAmber,
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(onClick = onResume)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resume task", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

