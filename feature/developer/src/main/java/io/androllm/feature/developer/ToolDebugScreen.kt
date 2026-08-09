package io.androllm.feature.developer

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
