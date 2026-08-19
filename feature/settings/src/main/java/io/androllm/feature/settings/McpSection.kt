package io.androllm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import io.androllm.core.mcp.McpConnectionManager
import io.androllm.core.mcp.McpServer
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.ui.theme.ledger

/**
 * "MCP Servers" — connect the assistant to Model Context Protocol servers
 * (Streamable HTTP). Each server's remote tools are imported into the tool
 * registry under `mcp_<server>_<tool>` names and become available to the
 * planner exactly like built-in tools.
 */
@Composable
fun McpSection(
    servers: List<McpServer>,
    states: Map<String, McpConnectionManager.State>,
    onAdd: (name: String, url: String, token: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onToggle: (server: McpServer, enabled: Boolean) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.ledger.lampGlow, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("MCP Servers", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.ledger.deskPaper)
                    Text(
                        "Import tools from external MCP servers (unlimited capabilities)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInk,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (servers.isEmpty()) {
                Text(
                    text = "No servers configured. Add your MCP server endpoint (Streamable HTTP, e.g. https://example.com/mcp) to import its tools.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                servers.forEachIndexed { index, server ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.15f))
                    McpServerRow(
                        server = server,
                        state = states[server.id],
                        onToggle = { onToggle(server, it) },
                        onRemove = { onRemove(server.id) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.15f))

            CloudCapsuleButton(
                text = "Add MCP Server",
                onClick = { showAddDialog = true },
                icon = Icons.Filled.Add,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, token ->
                showAddDialog = false
                onAdd(name, url, token)
            }
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServer,
    state: McpConnectionManager.State?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                server.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.ledger.deskPaper,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                server.url,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stateLabel(state),
                style = MaterialTheme.typography.labelSmall.copy(color = stateColor(state))
            )
        }
        Switch(
            checked = server.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.ledger.lampAmber)
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove ${server.name}",
                tint = MaterialTheme.ledger.deskInkFaint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun stateLabel(state: McpConnectionManager.State?): String = when (state) {
    null, McpConnectionManager.State.Disconnected -> "Offline"
    McpConnectionManager.State.Connecting -> "Connecting…"
    is McpConnectionManager.State.Connected -> "Connected • ${state.toolCount} tool${if (state.toolCount == 1) "" else "s"}"
    is McpConnectionManager.State.Failed -> "Failed — ${state.message.take(40)}"
}

@Composable
private fun stateColor(state: McpConnectionManager.State?): Color = when (state) {
    is McpConnectionManager.State.Connected -> MaterialTheme.ledger.lampDeep
    McpConnectionManager.State.Connecting -> MaterialTheme.ledger.lampAmber
    is McpConnectionManager.State.Failed -> MaterialTheme.ledger.lampAmber
    else -> MaterialTheme.ledger.deskInkFaint
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, token: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP Server", fontWeight = FontWeight.Bold, color = MaterialTheme.ledger.deskPaper) },
        text = {
            Column {
                Text(
                    text = "Point at an MCP server exposing the Streamable HTTP transport. Its tools become available to the assistant as mcp_<server>_<tool>.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (https://…/mcp)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, token) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.ledger.lampAmber)
            ) {
                Text("Connect", color = MaterialTheme.ledger.deskPaper)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.ledger.deskInkFaint) }
        }
    )
}
