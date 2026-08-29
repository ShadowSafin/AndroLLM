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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.tools.PlanStep
import io.androllm.feature.coding.tools.PlanStepStatus

/**
 * Inline plan approval card. Shown above the chat when the agent has proposed
 * a plan and is waiting for the user to review / edit / approve / reject it.
 *
 * The user can:
 *  - tap a step to rename it (inline editor);
 *  - reorder steps with the ▲ / ▼ buttons;
 *  - remove a step with ×;
 *  - add a new step at the bottom;
 *  - approve (the agent proceeds) or reject (the agent is told to revise).
 */
@Composable
fun PlanApprovalCard(
    draft: List<PlanStep>,
    onEditStep: (stepId: String, newText: String) -> Unit,
    onAddStep: (text: String) -> Unit,
    onRemoveStep: (stepId: String) -> Unit,
    onMoveStep: (stepId: String, delta: Int) -> Unit,
    onApprove: (editedDraft: List<PlanStep>) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var adding by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "REVIEW THE PLAN",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampDeep,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        "Edit, reorder, remove or add steps — then approve to let the agent start.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Step list
            draft.forEachIndexed { idx, step ->
                EditableStepRow(
                    step = step,
                    canMoveUp = idx > 0,
                    canMoveDown = idx < draft.lastIndex,
                    onEdit = { newText -> onEditStep(step.id, newText) },
                    onRemove = { onRemoveStep(step.id) },
                    onMoveUp = { onMoveStep(step.id, -1) },
                    onMoveDown = { onMoveStep(step.id, +1) }
                )
                Spacer(Modifier.height(4.dp))
            }

            // Add step
            if (adding) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = newText,
                        onValueChange = { newText = it },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.ledger.deskHairlineSoft)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        textStyle = TextStyle(color = MaterialTheme.ledger.deskPaper, fontSize = 14.sp),
                        singleLine = true
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            onAddStep(newText)
                            newText = ""
                            adding = false
                        },
                        enabled = newText.isNotBlank()
                    ) { Text("Add", color = MaterialTheme.ledger.lampAmber, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { adding = false; newText = "" }) {
                        Text("Cancel", color = MaterialTheme.ledger.deskInkFaint)
                    }
                }
            } else {
                TextButton(
                    onClick = { adding = true },
                    modifier = Modifier.padding(start = 36.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.ledger.lampDeep)
                    Spacer(Modifier.width(4.dp))
                    Text("Add a step", color = MaterialTheme.ledger.lampDeep, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onReject) {
                    Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.ledger.deskInk)
                    Spacer(Modifier.width(4.dp))
                    Text("Reject", color = MaterialTheme.ledger.deskInk, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.ledger.lampAmber)
                        .clickable { onApprove(draft) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Approve & start", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableStepRow(
    step: PlanStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var editing by remember(step.id) { mutableStateOf(false) }
    var text by remember(step.id) { mutableStateOf(step.text) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.ledger.lampDeep.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (step.status == PlanStepStatus.DONE) "✓" else "•",
                color = MaterialTheme.ledger.lampDeep,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        Spacer(Modifier.width(8.dp))
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.ledger.deskHairlineSoft)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                textStyle = TextStyle(color = MaterialTheme.ledger.deskPaper, fontSize = 14.sp),
                singleLine = true
            )
            IconButton(onClick = { onEdit(text); editing = false }) {
                Icon(Icons.Filled.Check, contentDescription = "Save", tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(16.dp))
            }
        } else {
            Text(
                step.text,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskPaper),
                modifier = Modifier
                    .weight(1f)
                    .clickable { text = step.text; editing = true }
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up", tint = if (canMoveUp) MaterialTheme.ledger.deskInk else MaterialTheme.ledger.deskInkFaint, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down", tint = if (canMoveDown) MaterialTheme.ledger.deskInk else MaterialTheme.ledger.deskInkFaint, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.ledger.deskInkFaint, modifier = Modifier.size(16.dp))
        }
    }
}
