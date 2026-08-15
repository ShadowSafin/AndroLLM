package io.androllm.feature.developer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.navigation.Routes
import io.androllm.core.tools.trace.ToolExecutionTrace
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tool Debug — the per-call execution log of the automation pipeline. Every
 * row answers the STEP 1 questions: which prompt, which tool, what arguments,
 * did it succeed, what did it return, how long, and what the LLM finally said.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDebugScreen(
    navController: NavController,
    viewModel: ToolDebugViewModel = hiltViewModel()
) {
    val traces by viewModel.traces.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = Routes.DEVELOPER,
            onTabSelected = { tab -> if (tab.route != Routes.DEVELOPER) navController.navigate(tab.route) },
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = DeskInk)
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = "Tool Execution Log",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DeskPaper
                                )
                            )
                            Text(
                                text = "${traces.size} call(s) • prompt → tool → result → LLM output",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = LampDeep,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp
                                )
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.clear() }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear log", tint = DeskInk)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Tool Registry status + prompt-injection diagnostics
                item {
                    ToolRegistryStatusCard(
                        diagnostics = diagnostics,
                        onRefresh = { viewModel.refresh() },
                        onProbeCapability = { viewModel.probeCapability() }
                    )
                }

                if (traces.isEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(40.dp))
                        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    tint = LampGlow,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No tool executions yet",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeskPaper
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Ask the assistant something like “Open Discord” or “Search the web for NVIDIA news”. Every tool call — the arguments, status, result, timing and final LLM output — will appear here.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = DeskInk)
                                )
                            }
                        }
                    }
                } else {
                    items(traces, key = { it.id }) { trace ->
                        TraceCard(trace)
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ToolRegistryStatusCard(
    diagnostics: ToolDiagnostics,
    onRefresh: () -> Unit,
    onProbeCapability: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Tool Registry",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = DeskPaper
                        )
                    )
                    Text(
                        text = "Registered tools • pipeline • prompt injection",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DeskInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh tool registry", tint = DeskInk, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status chips: pipeline, registered, advertised
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudChip(
                    text = if (diagnostics.pipelineEnabled) "● Pipeline ON" else "○ Pipeline OFF",
                    accentColor = if (diagnostics.pipelineEnabled) LampGlow else LampAmber
                )
                CloudChip(
                    text = "${diagnostics.registeredCount} registered",
                    accentColor = LampDeep
                )
                CloudChip(
                    text = "${diagnostics.advertisedCount} advertised",
                    accentColor = if (diagnostics.advertisedCount > 0) LampGlow else LampAmber
                )
            }

            // Prompt injection status
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (diagnostics.advertisementPreview.isNotBlank())
                    "PROMPT INJECTION — the system prompt advertises these tools:"
                else
                    "PROMPT INJECTION — nothing advertised (pipeline off, or every tool blocked)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (diagnostics.advertisementPreview.isNotBlank()) LampGlow else LampAmber,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
            )
            if (diagnostics.advertisementPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = diagnostics.advertisementPreview,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = DeskInk,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 5,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // ── Model tool-call capability ────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = DeskInk.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))
            val cap = diagnostics.capability
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Model Tool Capability",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DeskPaper,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = when {
                            cap.probeStatus.isNotBlank() -> "Probe: ${cap.probeStatus}"
                            cap.planningRounds == 0 -> "No planning rounds yet"
                            cap.nativeJsonSupport -> "Native JSON tool calling"
                            else -> "Parser compatibility mode"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (cap.nativeJsonSupport) LampGlow else LampAmber
                        )
                    )
                }
                TextButton(onClick = onProbeCapability) {
                    Text("Probe model")
                }
            }
            if (cap.planningRounds > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Model: ${cap.modelName.ifBlank { "—" }} • " +
                        "${cap.planningRounds} round${if (cap.planningRounds == 1) "" else "s"}: " +
                        "${cap.cleanParses} clean / ${cap.fallbackParses} parser-salvaged",
                    style = MaterialTheme.typography.labelSmall.copy(color = DeskInkFaint)
                )
            }
            if (cap.lastOutputSample.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = cap.lastOutputSample,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = DeskInk,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // Registered tools (tap a row to expand its function schema)
            if (diagnostics.tools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = DeskInk.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(8.dp))
                var expanded by remember { mutableStateOf<String?>(null) }
                diagnostics.tools.forEach { tool ->
                    val isExpanded = expanded == tool.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (isExpanded) null else tool.name }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (tool.enabled) "●" else "○",
                            color = if (tool.enabled) LampGlow else LampAmber,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeskPaper
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (tool.requiresConfirmation) {
                            Text(
                                text = "confirm",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = LampAmber,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = tool.category,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = LampDeep,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = DeskInk,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, bottom = 8.dp)
                        ) {
                            Text(
                                text = tool.description,
                                style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = tool.parametersJson.ifBlank { "{}" },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = LampDeep,
                                    fontFamily = FontFamily.Monospace
                                ),
                                maxLines = 8,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceCard(trace: ToolExecutionTrace) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: tool name + status chip + duration.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = trace.toolName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = DeskPaper
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${trace.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DeskInk,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CloudChip(
                        text = trace.status,
                        accentColor = when (trace.status) {
                            "ok" -> LampGlow
                            "blocked" -> LampAmber
                            else -> EmberRed
                        }
                    )
                }
            }

            // Prompt line.
            val prompt = trace.prompt
            if (!prompt.isNullOrBlank()) {
                TraceRow(label = "Prompt", value = prompt)
            }
            // Arguments line.
            if (trace.arguments.isNotBlank()) {
                TraceRow(label = "Args", value = trace.arguments)
            }
            // Result line.
            if (trace.result.isNotBlank()) {
                TraceRow(label = "Result", value = trace.result, color = DeskPaper)
            }
            // Error line (red).
            val error = trace.error
            if (!error.isNullOrBlank()) {
                TraceRow(label = "Error", value = error, color = EmberRed)
            }
            // LLM output line.
            val llmOutput = trace.llmOutput
            if (!llmOutput.isNullOrBlank()) {
                TraceRow(label = "LLM out", value = llmOutput)
            }

            // Footer: timestamp.
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = TIME.format(Date(trace.at)),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = LampDeep,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

@Composable
private fun TraceRow(label: String, value: String, color: Color = DeskInk) {
    Spacer(modifier = Modifier.height(6.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = LampDeep,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = color,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
