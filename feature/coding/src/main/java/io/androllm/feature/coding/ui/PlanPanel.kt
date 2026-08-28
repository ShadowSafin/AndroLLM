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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.tools.PlanStep
import io.androllm.feature.coding.tools.PlanStepStatus

/**
 * Premium task plan panel — spacious, consistent with other cards, with a
 * progress bar, clear hierarchy, and larger touch targets for mobile.
 */
@Composable
fun PlanPanel(
    plan: List<PlanStep>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val done = plan.count { it.status == PlanStepStatus.DONE }
    val progress = if (plan.isNotEmpty()) done.toFloat() / plan.size else 0f

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
                    Icon(Icons.Filled.Assignment, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "TASK PLAN",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampDeep,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        if (plan.isEmpty()) "Agent will create a plan for multi-step tasks"
                        else "$done of ${plan.size} steps complete",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                }
                if (plan.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (done == plan.size) Color(0xFF34C759).copy(alpha = 0.12f)
                                else MaterialTheme.ledger.lampAmber.copy(alpha = 0.12f)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "$done/${plan.size}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (done == plan.size) Color(0xFF1B7A2B) else MaterialTheme.ledger.lampDeep
                            )
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Close plan", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(20.dp))
                }
            }

            if (plan.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (done == plan.size) Color(0xFF34C759) else MaterialTheme.ledger.lampAmber,
                    trackColor = MaterialTheme.ledger.deskHairlineSoft
                )
                Spacer(Modifier.height(10.dp))
            } else {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.ledger.deskWalnutDeep)
                        .padding(14.dp)
                ) {
                    Text(
                        "No plan yet. For multi-step tasks the agent creates one here and checks steps off as it works — you can watch progress live.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk, lineHeight = 16.sp)
                    )
                }
                return@Card
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.ledger.deskWalnutDeep)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                plan.forEachIndexed { index, step -> PlanStepRow(number = index + 1, step = step) }
            }
        }
    }
}

@Composable
private fun PlanStepRow(number: Int, step: PlanStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when (step.status) {
                    PlanStepStatus.IN_PROGRESS -> MaterialTheme.ledger.lampAmber.copy(alpha = 0.10f)
                    PlanStepStatus.DONE -> Color(0xFF34C759).copy(alpha = 0.06f)
                    PlanStepStatus.PENDING -> Color.Transparent
                }
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when (step.status) {
                        PlanStepStatus.DONE -> Color(0xFF34C759)
                        PlanStepStatus.IN_PROGRESS -> MaterialTheme.ledger.lampAmber
                        PlanStepStatus.PENDING -> MaterialTheme.ledger.deskHairline
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (step.status) {
                PlanStepStatus.DONE -> Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                PlanStepStatus.IN_PROGRESS -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
                PlanStepStatus.PENDING -> Text("$number", color = MaterialTheme.ledger.deskInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            step.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = when (step.status) {
                    PlanStepStatus.DONE -> MaterialTheme.ledger.deskInkFaint
                    PlanStepStatus.IN_PROGRESS -> MaterialTheme.ledger.lampDeep
                    PlanStepStatus.PENDING -> MaterialTheme.ledger.deskPaper
                },
                fontWeight = if (step.status == PlanStepStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Medium,
                textDecoration = if (step.status == PlanStepStatus.DONE) TextDecoration.LineThrough else TextDecoration.None,
                lineHeight = 16.sp
            ),
            modifier = Modifier.weight(1f)
        )
        if (step.status == PlanStepStatus.IN_PROGRESS) {
            Spacer(Modifier.width(8.dp))
            Text(
                "IN PROGRESS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.lampDeep,
                    letterSpacing = 0.8.sp
                )
            )
        }
    }
}
