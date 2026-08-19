package io.androllm.feature.chat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.DeskWalnutRaised
import io.androllm.core.ui.theme.InkOnLamp
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.ui.theme.ledger

/**
 * In-chat card shown while a high-risk tool action (SMS, call, email…)
 * awaits the user's approval. Approving resumes the suspended executor;
 * denying lets the LLM explain why the action was skipped.
 */
@Composable
fun ToolConfirmationCard(
    confirmation: PendingToolConfirmation,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.ledger.deskWalnutRaised,
        border = BorderStroke(1.dp, MaterialTheme.ledger.lampGlow.copy(alpha = 0.35f)),
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.lampAmber,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "ACTION REQUIRED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.lampGlow
                    )
                )
            }
            Text(
                text = confirmation.toolDisplayName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
            Text(
                text = confirmation.actionSummary,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInk)
            )
            Text(
                text = if (confirmation.requiredPermissions.isNotEmpty()) {
                    "This action can't be undone. Tapping Approve will ask for the system " +
                        "permission this tool needs, then run it. Deny to cancel."
                } else {
                    "This action can't be undone. Tap Approve to continue or Deny to cancel."
                },
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDeny) {
                    Text("Deny", color = MaterialTheme.ledger.deskInkFaint)
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.ledger.lampAmber,
                        contentColor = MaterialTheme.ledger.inkOnLamp
                    )
                ) {
                    Text("Approve", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
