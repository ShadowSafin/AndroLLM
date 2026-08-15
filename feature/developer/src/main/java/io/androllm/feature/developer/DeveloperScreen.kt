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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Troubleshoot
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
import io.androllm.core.common.UiState
import io.androllm.core.navigation.Routes
import io.androllm.core.ui.components.CloudAdaptiveNavigation
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudBarChart
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.CloudLineChart
import io.androllm.core.ui.components.CloudUsageBar
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.runtime.Runtime
import io.androllm.core.runtime.RuntimeStatus
import io.androllm.core.ui.theme.LampGlow
import io.androllm.engine.api.EngineState

/**
 * Developer Mode — the desk's diagnostic drawer. Every chart is backed by
 * session telemetry from the native engine and the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    navController: NavController,
    viewModel: DeveloperViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val debugInfo by viewModel.debugInfo.collectAsStateWithLifecycle()
    val memoryStats by viewModel.memoryStats.collectAsStateWithLifecycle()
    val recentMemories by viewModel.recentMemories.collectAsStateWithLifecycle()
    val runtimeStatuses by viewModel.runtimeStatuses.collectAsStateWithLifecycle()
    val systemMemory by viewModel.systemMemory.collectAsStateWithLifecycle()
    val data = (uiState as? UiState.Success)?.data ?: DeveloperData()

    CloudAtmosphericBackground {
        CloudAdaptiveNavigation(
            currentRoute = Routes.DEVELOPER,
            onTabSelected = { tab -> if (tab.route != Routes.DEVELOPER) navController.navigate(tab.route) },
            topBar = {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    title = {
                        Column {
                            Text(
                                text = "Developer Mode",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DeskPaper
                                )
                            )
                            Text(
                                text = "Live engine & device telemetry",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = LampDeep,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp
                                )
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = DeskInk)
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
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Device & engine summary
                item {
                    DeviceSummaryCard(data = data)
                }

                // Tool execution log entry point
                item {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate(Routes.TOOL_DEBUG) }
                                .padding(vertical = 16.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = LampGlow,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Tool Execution Log",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeskPaper
                                    )
                                )
                                Text(
                                    text = "Every automation call: prompt → tool → result → LLM output",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = DeskInk
                                    )
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = DeskInk
                            )
                        }
                    }
                }

                // Throughput
                item {
                    ChartCard(
                        title = "Inference Throughput",
                        liveValue = "${String.format("%.1f", data.lastTokensPerSecond)} tok/s",
                        subtitle = "Peak ${String.format("%.1f", data.peakTokensPerSecond)} • Avg ${String.format("%.1f", data.avgTokensPerSecond)} • ${data.speedHistory.size} samples"
                    ) {
                        CloudLineChart(
                            dataPoints = data.speedHistory.ifEmpty { listOf(0f, 0f) },
                            accent = LampGlow,
                            height = 120.dp
                        )
                    }
                }

                // RAM pressure
                item {
                    ChartCard(
                        title = "RAM Pressure",
                        liveValue = "${data.history.lastOrNull()?.ramUsedMb?.toInt() ?: 0} MB",
                        subtitle = "of ${data.deviceMetrics?.totalRamMb ?: 0} MB device RAM"
                    ) {
                        CloudLineChart(
                            dataPoints = data.ramHistory.ifEmpty { listOf(0f, 0f) },
                            accent = LampAmber,
                            height = 110.dp
                        )
                    }
                }

                // GPU & KV cache
                item {
                    ChartCard(
                        title = "GPU & KV Cache",
                        liveValue = data.memoryStats?.let { "${it.gpuMemoryMb().toInt()} MB GPU" } ?: "—",
                        subtitle = data.memoryStats?.let { "${it.contextSizeMb().toInt()} MB KV cache • ${it.gpuBufferCount} buffers" } ?: "Idle — load a model"
                    ) {
                        Column {
                            CloudLineChart(
                                dataPoints = data.gpuHistory.ifEmpty { listOf(0f, 0f) },
                                accent = LampGlow,
                                height = 90.dp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            // KV cache as a real share of total native memory
                            // (model + context), not an arbitrary fraction.
                            val totalNativeMb = data.memoryStats?.totalNativeMb() ?: 0f
                            CloudUsageBar(
                                label = "KV cache",
                                valueText = "${data.memoryStats?.contextSizeMb()?.toInt() ?: 0} MB of ${totalNativeMb.toInt()} MB native",
                                fraction = if (totalNativeMb > 0f) {
                                    (data.memoryStats?.contextSizeMb() ?: 0f) / totalNativeMb
                                } else 0f,
                                accent = LampAmber
                            )
                        }
                    }
                }

                // Context window
                item {
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Context Window",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeskPaper
                                    )
                                )
                                Text(
                                    text = "${data.contextTokensUsed} / ${data.contextLength} tokens",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (data.contextUsageFraction > 0.85f) EmberRed else LampGlow,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            CloudUsageBar(
                                label = "Used",
                                valueText = "${(data.contextUsageFraction * 100).toInt()}%",
                                fraction = data.contextUsageFraction,
                                accent = if (data.contextUsageFraction > 0.85f) EmberRed else LampGlow
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val promptTokens = data.lastStats?.promptTokens ?: 0L
                            val generatedTokens = data.lastStats?.generatedTokens ?: 0L
                            Text(
                                text = "Last generation: $promptTokens prompt + $generatedTokens generated tokens",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = DeskInk
                                )
                            )
                        }
                    }
                }

                // Generation history
                item {
                    SectionHeader(
                        title = "Generation History",
                        subtitle = "${data.generations.size} runs this session"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                text = "Latency per generation (ms)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeskInk
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            CloudBarChart(
                                values = data.generationLatencies.ifEmpty { listOf(0f, 0f) },
                                accent = LampGlow,
                                height = 110.dp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Tokens/sec per generation",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeskInk
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            CloudBarChart(
                                values = data.generationSpeeds.ifEmpty { listOf(0f, 0f) },
                                accent = LampAmber,
                                height = 110.dp
                            )
                        }
                    }
                }

                // Backend diagnostics
                item {
                    BackendDiagnosticsCard(
                        data = data,
                        onRefresh = { viewModel.refreshDebugInfo() },
                        debugInfo = debugInfo,
                        systemMemory = systemMemory
                    )
                }

                // Memory Inspector
                item {
                    SectionHeader(
                        title = "Memory Inspector",
                        subtitle = "On-device memory pipeline"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MemoryInspectorCard(
                        stats = memoryStats,
                        recentMemories = recentMemories,
                        onRefresh = { viewModel.refreshMemoryInspector() }
                    )
                }

                // Runtime Registry — every app runtime, auto-discovered
                item {
                    SectionHeader(
                        title = "Runtime Registry",
                        subtitle = "Every app runtime, auto-discovered — failures stay isolated"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    RuntimeRegistryCard(
                        runtimes = runtimeStatuses,
                        onRefresh = { viewModel.refreshRuntimes() }
                    )
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DeviceSummaryCard(data: DeveloperData) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Troubleshoot,
                contentDescription = null,
                tint = LampGlow,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.modelName.ifBlank { "No model loaded" },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeskPaper
                    )
                )
                Text(
                    text = buildString {
                        append(data.deviceMetrics?.deviceModel ?: "Device")
                        append(" • Android ${data.deviceMetrics?.androidVersion ?: "?"}")
                        append(" • ${data.deviceMetrics?.cpuCores ?: 0} cores")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = DeskInk
                    )
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                CloudChip(
                    text = "Backend ${data.backendLabel}",
                    accentColor = if (data.backendLabel.contains("VULKAN", ignoreCase = true)) LampGlow else LampAmber,
                    icon = Icons.Filled.Bolt
                )
                Spacer(modifier = Modifier.height(6.dp))
                CloudChip(
                    text = when (data.engineState) {
                        is EngineState.Ready -> "● Ready"
                        is EngineState.Generating -> "● Generating"
                        is EngineState.Loading -> "Loading…"
                        is EngineState.WarmingUp -> "Warming up…"
                        EngineState.Unloading -> "Unloading…"
                        is EngineState.Failed -> "● Error"
                        EngineState.Unloaded -> "Idle"
                    },
                    accentColor = when (data.engineState) {
                        is EngineState.Ready, is EngineState.Generating -> LampGlow
                        is EngineState.Failed -> EmberRed
                        else -> LampDeep
                    },
                    icon = Icons.Filled.Memory
                )
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    liveValue: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeskPaper
                        )
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DeskInk
                        )
                    )
                }
                Text(
                    text = liveValue,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = LampAmber
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun BackendDiagnosticsCard(
    data: DeveloperData,
    debugInfo: io.androllm.engine.models.EngineDebugInfo?,
    systemMemory: SystemMemoryInfo?,
    onRefresh: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Backend Diagnostics",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeskPaper
                    )
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Speed, contentDescription = "Refresh diagnostics", tint = DeskInk, modifier = Modifier.size(18.dp))
                }
            }

            val info = debugInfo
            DiagRow("Backend", data.backendLabel)
            DiagRow("GPU", info?.gpuName?.ifBlank { data.memoryStats?.gpuName } ?: "—")
            DiagRow("GPU layers", info?.let { "${it.gpuLayers}/${it.totalLayers}" } ?: data.memoryStats?.gpuLayersDisplay ?: "—")
            DiagRow("Threads", info?.nThreads?.takeIf { it > 0 }?.toString() ?: data.deviceMetrics?.cpuCores?.toString() ?: "—")
            DiagRow("KV type", info?.kvType ?: "—")
            DiagRow("Flash attention", info?.flashAttn ?: "—")
            DiagRow("Sampler", info?.sampler ?: "—")
            DiagRow("First token latency", info?.firstTokenMs?.takeIf { it > 0 }?.let { "${it} ms" } ?: "—")
            DiagRow("Stop reason", data.lastStats?.stopReason ?: "—")
            DiagRow("Total inference", data.lastStats?.totalTimeMs?.let { "${it} ms" } ?: "—")
            DiagRow("Prompt tokens", data.lastStats?.promptTokens?.toString() ?: "—")
            DiagRow("Generated tokens", data.lastStats?.generatedTokens?.toString() ?: "—")
            DiagRow("Chat template", info?.let { if (it.templateReady) "Ready" else "Unavailable" } ?: "—")

            // System RAM + native heap: the budgets the pre-load resource guard
            // actually checks (llama.cpp memory is native, not Java heap).
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = DeskInk.copy(alpha = 0.15f)
            )
            DiagRow(
                "System RAM",
                systemMemory?.let {
                    "${it.totalRamMb()} MB total • ${it.availableRamMb()} MB free" + if (it.lowMemory) " • LOW" else ""
                } ?: "—"
            )
            DiagRow(
                "Native heap",
                systemMemory?.let { "${it.nativeHeapAllocatedMb()} MB of ${it.nativeHeapSizeMb()} MB" } ?: "—"
            )
            DiagRow(
                "Model RAM estimate",
                data.memoryStats?.let {
                    "${it.modelSizeMb().toInt()} MB weights + ${it.contextSizeMb().toInt()} MB KV"
                } ?: "—"
            )
        }
    }
}

@Composable
private fun MemoryInspectorCard(
    stats: io.androllm.core.memory.model.MemoryInspectorStats?,
    recentMemories: List<io.androllm.core.memory.model.Memory>,
    onRefresh: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Memory Store",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeskPaper
                    )
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh memory inspector", tint = DeskInk, modifier = Modifier.size(18.dp))
                }
            }

            if (stats == null) {
                Text(
                    text = "Memory system is idle — enable it in Settings.",
                    style = MaterialTheme.typography.bodySmall.copy(color = DeskInk)
                )
            } else {
                if (!stats.enabled) {
                    Text(
                        text = "Disabled in Settings",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = LampAmber,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Counts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatMini("Memories", stats.memoryCount.toString(), Modifier.weight(1f))
                    StatMini("Embeddings", stats.embeddingCount.toString(), Modifier.weight(1f))
                    StatMini("Vectors", stats.vectorCount.toString(), Modifier.weight(1f))
                    StatMini("Summaries", stats.summaryCount.toString(), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatMini("Projects", stats.projectCount.toString(), Modifier.weight(1f))
                    StatMini("Tags", stats.tagCount.toString(), Modifier.weight(1f))
                    StatMini("Links", stats.relationshipCount.toString(), Modifier.weight(1f))
                    StatMini("Extractions", stats.totalExtractions.toString(), Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(14.dp))
                DiagRow("Avg retrieval", "${stats.avgRetrievalMs} ms")
                DiagRow("Last retrieval", "${stats.lastRetrievalMs} ms")
                DiagRow("Last embedding", "${stats.lastEmbeddingMs} ms")
                DiagRow("Last extraction", "${stats.lastExtractionMs} ms")
                DiagRow("Inserted / Updated", "${stats.totalInserted} / ${stats.totalUpdated}")
                DiagRow("Similarity threshold", "${(stats.similarityThreshold * 100).toInt()}%")
                DiagRow("Retrieved per prompt", stats.retrievalCount.toString())
                DiagRow(
                    "Embedding model",
                    if (stats.embeddingModelPath.isNotBlank()) {
                        stats.embeddingModelPath.substringAfterLast('/')
                            .ifBlank { stats.embeddingModelPath }
                    } else {
                        "Not configured"
                    }
                )
                DiagRow(
                    "Model status",
                    if (stats.embeddingModelLoaded) "Loaded (dim ${stats.embeddingDimension})" else "Not loaded"
                )

                // Context preview
                if (recentMemories.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Context preview (most recent)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeskInk
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    for (memory in recentMemories) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = memory.category.name.take(4).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = LampGlow,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.width(44.dp)
                            )
                            Text(
                                text = memory.content.take(80),
                                style = MaterialTheme.typography.bodySmall.copy(color = DeskPaper),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (memory.isPinned) {
                                Text(
                                    text = "PIN",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = LampAmber,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }

                // Extraction logs
                if (stats.logs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Extraction logs",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeskInk
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    for (entry in stats.logs.take(8)) {
                        Text(
                            text = entry.message.take(120),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = when (entry.level) {
                                    io.androllm.core.memory.model.MemoryLogLevel.ERROR -> EmberRed
                                    io.androllm.core.memory.model.MemoryLogLevel.WARN -> LampAmber
                                    else -> DeskInk
                                }
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeRegistryCard(
    runtimes: List<Pair<Runtime, RuntimeStatus>>,
    onRefresh: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Registered Runtimes",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = DeskPaper
                    )
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh runtimes", tint = DeskInk, modifier = Modifier.size(18.dp))
                }
            }

            if (runtimes.isEmpty()) {
                Text(
                    text = "Collecting runtime status…",
                    style = MaterialTheme.typography.bodySmall.copy(color = DeskInk)
                )
            } else {
                runtimes.forEachIndexed { index, (runtime, status) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = runtime.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeskPaper
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                CloudChip(
                                    text = runtime.category.displayName,
                                    accentColor = LampDeep
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = status.summary,
                                style = MaterialTheme.typography.labelSmall.copy(color = DeskInk)
                            )
                            status.detail?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall.copy(color = LampDeep),
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (status.available) "● Ready" else "● Off",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (status.available) LampGlow else LampDeep
                            )
                        )
                    }
                    if (index < runtimes.lastIndex) {
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = DeskInk.copy(alpha = 0.15f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatMini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                color = LampAmber
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = DeskInk),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = DeskInk
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = DeskPaper
        )
    }
}
