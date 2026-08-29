package io.androllm.feature.coding.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger

/**
 * Premium terminal panel — raw output verbatim (stdout + stderr, exit code,
 * duration) with search, copy, cancel and clear controls.
 *
 * The search field filters the visible entries by substring (case-insensitive,
 * matches command OR output). All entries remain in the underlying history;
 * only the rendered list is filtered.
 */
@Composable
fun TerminalPanel(
    lines: List<TerminalLine>,
    runningCommand: String?,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(lines, query) {
        if (query.isBlank()) lines
        else lines.filter { it.command.contains(query, ignoreCase = true) || it.output.contains(query, ignoreCase = true) }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0C)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A1A18)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, tint = Color(0xFF5FCF3D), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "TERMINAL",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE8E4DC),
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        when {
                            runningCommand != null -> "Running: $runningCommand"
                            query.isNotBlank() -> "${filtered.size}/${lines.size} match '${query}'"
                            lines.isEmpty() -> "No commands yet"
                            else -> "${lines.size} commands • raw output preserved"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF8A8478)),
                        maxLines = 1
                    )
                }
                if (runningCommand != null) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Stop, "Cancel command", tint = Color(0xFFE0604A), modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(
                    onClick = { copyAllToClipboard(context, lines) },
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, "Copy all", tint = Color(0xFFA39D92), modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClear, enabled = lines.isNotEmpty(), modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, "Clear", tint = Color(0xFFA39D92), modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Close", tint = Color(0xFFA39D92), modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            // Search bar.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search command or output", color = Color(0xFF6B6660), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF8A8478)) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Color(0xFFA39D92), modifier = Modifier.size(16.dp)) } }
                } else null,
                textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFFE8E4DC)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF5FCF3D).copy(alpha = 0.5f),
                    unfocusedBorderColor = Color(0xFF1A1A18),
                    cursorColor = Color(0xFF5FCF3D)
                )
            )

            Spacer(Modifier.height(8.dp))

            if (runningCommand != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFF59E0B)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$ $runningCommand",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEAA48C),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("running...", fontSize = 11.sp, color = Color(0xFFD97706), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1A1A18))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (lines.isEmpty()) "No commands run yet." else "No matches for '${query}'.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8A8478)
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(filtered, key = { it.id }) { line -> TerminalEntry(line, query) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalEntry(line: TerminalLine, highlight: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1A18))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$ ${line.command}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFEAA48C),
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            line.cancelled -> Color(0xFFF59E0B).copy(alpha = 0.2f)
                            line.exitCode == 0 -> Color(0xFF34C759).copy(alpha = 0.2f)
                            else -> Color(0xFFEF4444).copy(alpha = 0.2f)
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    when {
                        line.cancelled -> "cancelled"
                        line.exitCode == 0 -> "exit 0"
                        else -> "exit ${line.exitCode}"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        line.cancelled -> Color(0xFFD97706)
                        line.exitCode == 0 -> Color(0xFF34C759)
                        else -> Color(0xFFEF4444)
                    }
                )
            }
        }
        if (line.output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                line.output,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = Color(0xFFE8E4DC)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${line.durationMs}ms",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF6B7280)
        )
    }
}

/** Copies the entire terminal history (commands + outputs) to the system clipboard. */
private fun copyAllToClipboard(context: Context, lines: List<TerminalLine>) {
    if (lines.isEmpty()) return
    val text = lines.joinToString(separator = "\n\n") { line ->
        buildString {
            append("$ ").append(line.command)
            if (line.output.isNotBlank()) append('\n').append(line.output)
            append('\n').append("[exit ").append(line.exitCode).append(" • ").append(line.durationMs).append("ms]")
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal output", text))
}
