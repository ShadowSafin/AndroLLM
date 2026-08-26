package io.androllm.feature.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.usage.CloudPricing
import io.androllm.core.cloud.usage.CloudProviderLifetimeStats
import io.androllm.core.cloud.usage.CloudModelLifetimeStats
import io.androllm.core.cloud.usage.CloudUsageAlert
import io.androllm.core.cloud.usage.CloudUsageRecord
import io.androllm.core.cloud.usage.CloudUsageSnapshot
import io.androllm.core.cloud.usage.CloudUsageTotals
import io.androllm.core.cloud.usage.CloudErrorKind
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.ledger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud Usage dashboard — the cloud control center: tokens, cost, latency,
 * provider health, tool calling, cache performance, limits/alerts and the
 * request history, with date/provider/model filters and export/clear actions.
 */
@Composable
fun CloudUsageDashboardScreen(
    navController: NavController,
    viewModel: CloudUsageDashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    CloudAtmosphericBackground(reduceMotion = false) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Cloud Usage",
                            color = MaterialTheme.ledger.deskPaper,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.ledger.deskPaper
                            )
                        }
                    },
                    actions = {
                        if (uiState.refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(2.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.ledger.lampAmber
                            )
                        } else {
                            IconButton(onClick = { viewModel.refresh() }) {
                                Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.ledger.deskPaper)
                            }
                        }
                        IconButton(onClick = { viewModel.exportUsage() }) {
                            Icon(Icons.Filled.Share, "Export", tint = MaterialTheme.ledger.deskPaper)
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.Delete, "Clear", tint = MaterialTheme.ledger.deskPaper)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            val snapshot = uiState.snapshot
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { FilterRow(uiState, viewModel) }

                if (snapshot == null) {
                    item {
                        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Loading cloud usage...",
                                color = MaterialTheme.ledger.deskInkFaint,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    item { OverviewSection(snapshot, uiState) }
                    if (snapshot.alerts.isNotEmpty()) {
                        item { AlertsSection(snapshot.alerts) }
                    }
                    item { TokensSection(snapshot) }
                    item { CostSection(snapshot) }
                    item { LatencySection(snapshot) }
                    item { ProviderHealthSection(uiState) }
                    item { ToolCallingSection(snapshot) }
                    item { CacheSection(uiState) }
                    if (snapshot.perModel.isNotEmpty()) {
                        item { ModelsSection(snapshot.perModel) }
                    }
                    item { HistorySection(uiState, viewModel) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cloud usage?") },
            text = { Text("This deletes all recorded cloud usage, cost history and the prompt cache. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearUsage()
                }) { Text("Clear", color = MaterialTheme.ledger.emberRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep") }
            }
        )
    }
}

// ── Filters ────────────────────────────────────────────────────────────────

@Composable
private fun FilterRow(
    uiState: CloudUsageDashboardViewModel.UiState,
    viewModel: CloudUsageDashboardViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageDateRange.entries.forEach { range ->
                FilterChip(
                    label = range.label,
                    selected = uiState.dateRange == range,
                    onClick = { viewModel.setDateRange(range) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderFilterDropdown(uiState, viewModel)
            ModelFilterDropdown(uiState, viewModel)
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.ledger.lampAmber
                else MaterialTheme.ledger.cloudGlassSurface
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskHairline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.ledger.inkOnLamp else MaterialTheme.ledger.deskPaperDim
        )
    }
}

@Composable
private fun ProviderFilterDropdown(
    uiState: CloudUsageDashboardViewModel.UiState,
    viewModel: CloudUsageDashboardViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val providers = uiState.settings.providers
    val selectedName = providers.find { it.id == uiState.providerFilter }?.name ?: "All providers"
    Box {
        FilterChip(label = selectedName, selected = uiState.providerFilter != null, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All providers") },
                onClick = {
                    viewModel.setProviderFilter(null)
                    expanded = false
                }
            )
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.name.ifBlank { provider.id }) },
                    onClick = {
                        viewModel.setProviderFilter(provider.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelFilterDropdown(
    uiState: CloudUsageDashboardViewModel.UiState,
    viewModel: CloudUsageDashboardViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val models = uiState.snapshot?.perModel?.map { it.modelId }.orEmpty().distinct()
    val label = uiState.modelFilter ?: "All models"
    Box {
        FilterChip(label = label, selected = uiState.modelFilter != null, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All models") },
                onClick = {
                    viewModel.setModelFilter(null)
                    expanded = false
                }
            )
            models.forEach { modelId ->
                DropdownMenuItem(
                    text = { Text(modelId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        viewModel.setModelFilter(modelId)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ── Overview ───────────────────────────────────────────────────────────────

@Composable
private fun OverviewSection(
    snapshot: CloudUsageSnapshot,
    uiState: CloudUsageDashboardViewModel.UiState
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Overview")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (snapshot.currentProviderName.isNotBlank()) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.ledger.lampDeep,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = snapshot.currentProviderName.ifBlank { "No provider selected" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.ledger.deskPaper
                        )
                        Text(
                            text = snapshot.currentModelId.ifBlank { "No model selected" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ledger.deskInkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Requests today", formatCount(snapshot.today.requests), Modifier.weight(1f))
                    MetricTile("Tokens today", formatTokens(snapshot.today.totalTokens), Modifier.weight(1f))
                    MetricTile("Cost today", CloudPricing.formatUsd(snapshot.today.estimatedCostMicros), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("This month", formatTokens(snapshot.month.totalTokens), Modifier.weight(1f))
                    MetricTile("Month cost", CloudPricing.formatUsd(snapshot.month.estimatedCostMicros), Modifier.weight(1f))
                    MetricTile("Active sessions", snapshot.activeSessions.toString(), Modifier.weight(1f))
                }
                SuccessRateRow(snapshot.today)
                LastRequestRow(snapshot.lastRequest, snapshot.lastProviderName)
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.ledger.deskWalnutDeep, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.ledger.deskPaper,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.ledger.deskInkFaint,
            maxLines = 1
        )
    }
}

@Composable
private fun SuccessRateRow(totals: CloudUsageTotals) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Success rate (today)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskInk)
            Text(
                "${(totals.successRate * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (totals.successRate >= 0.95f) MaterialTheme.ledger.revolutNeonEmerald else MaterialTheme.ledger.emberRed
            )
        }
        Spacer(Modifier.height(4.dp))
        UsageRatioBar(
            fraction = totals.successRate,
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (totals.successRate >= 0.95f) MaterialTheme.ledger.revolutNeonEmerald else MaterialTheme.ledger.emberRed
        )
    }
}

@Composable
private fun LastRequestRow(last: CloudUsageRecord?, providerName: String) {
    if (last == null) {
        Text(
            "No cloud requests yet — send a message in cloud mode.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ledger.deskInkFaint
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (last.success) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (last.success) MaterialTheme.ledger.revolutNeonEmerald else MaterialTheme.ledger.emberRed,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = buildString {
                append(if (last.success) "Last request OK" else "Last request failed")
                if (providerName.isNotBlank()) append(" · $providerName")
                append(" · ${formatTime(last.timestampMs)}")
                if (!last.success && last.errorMessage.isNotBlank()) append(" · ${last.errorMessage.take(60)}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ledger.deskInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Alerts ─────────────────────────────────────────────────────────────────

@Composable
private fun AlertsSection(alerts: List<CloudUsageAlert>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Limits & Alerts")
        alerts.forEach { alert ->
            CloudGlassCard(
                modifier = Modifier.fillMaxWidth(),
                borderColor = when (alert.severity) {
                    CloudUsageAlert.Severity.CRITICAL -> MaterialTheme.ledger.emberRed
                    CloudUsageAlert.Severity.WARNING -> MaterialTheme.ledger.lampAmber
                    CloudUsageAlert.Severity.INFO -> MaterialTheme.ledger.deskHairline
                }
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (alert.severity == CloudUsageAlert.Severity.INFO) Icons.Filled.Info else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = when (alert.severity) {
                            CloudUsageAlert.Severity.CRITICAL -> MaterialTheme.ledger.emberRed
                            CloudUsageAlert.Severity.WARNING -> MaterialTheme.ledger.lampAmber
                            CloudUsageAlert.Severity.INFO -> MaterialTheme.ledger.deskInk
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(alert.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.ledger.deskPaper)
                        Text(alert.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskInk)
                    }
                }
            }
        }
    }
}

// ── Tokens ─────────────────────────────────────────────────────────────────

@Composable
private fun TokensSection(snapshot: CloudUsageSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Tokens")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Input", formatTokens(snapshot.total.inputTokens), Modifier.weight(1f))
                    MetricTile("Output", formatTokens(snapshot.total.outputTokens), Modifier.weight(1f))
                    MetricTile("Total", formatTokens(snapshot.total.totalTokens), Modifier.weight(1f))
                }
                Text("Daily tokens (last 14 days)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.ledger.deskInk)
                UsageBarChart(
                    values = snapshot.daily.map { it.totalTokens.toFloat() },
                    labels = listOf(
                        snapshot.daily.firstOrNull()?.dateKey?.takeLast(5).orEmpty(),
                        snapshot.daily.lastOrNull()?.dateKey?.takeLast(5).orEmpty()
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ── Cost ───────────────────────────────────────────────────────────────────

@Composable
private fun CostSection(snapshot: CloudUsageSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Cost (estimated)")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Today", CloudPricing.formatUsd(snapshot.today.estimatedCostMicros), Modifier.weight(1f))
                    MetricTile("This month", CloudPricing.formatUsd(snapshot.month.estimatedCostMicros), Modifier.weight(1f))
                    MetricTile("All time", CloudPricing.formatUsd(snapshot.total.estimatedCostMicros), Modifier.weight(1f))
                }
                Text("Daily cost trend", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.ledger.deskInk)
                UsageLineChart(
                    values = snapshot.daily.map { it.estimatedCostMicros.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(96.dp)
                )
                if (snapshot.perProvider.isNotEmpty()) {
                    Text("Cost by provider", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.ledger.deskInk)
                    snapshot.perProvider.take(5).forEach { provider ->
                        StatRow(
                            label = provider.providerName.ifBlank { provider.providerId },
                            value = CloudPricing.formatUsd(provider.estimatedCostMicros)
                        )
                    }
                }
            }
        }
    }
}

// ── Latency ────────────────────────────────────────────────────────────────

@Composable
private fun LatencySection(snapshot: CloudUsageSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Latency")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Avg latency", formatMs(snapshot.total.avgLatencyMs), Modifier.weight(1f))
                    MetricTile("First token", formatMs(snapshot.total.avgFirstTokenMs), Modifier.weight(1f))
                    MetricTile("Retries", snapshot.total.retries.toString(), Modifier.weight(1f))
                }
                Text("Latency trend", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.ledger.deskInk)
                UsageLineChart(
                    values = snapshot.daily.map { it.avgLatencyMs.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    lineColor = MaterialTheme.ledger.revolutCyberCyan
                )
                Text("Requests by hour (today)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.ledger.deskInk)
                UsageBarChart(
                    values = snapshot.hourlyRequests.map { it.toFloat() },
                    labels = listOf("00", "06", "12", "18", "23"),
                    modifier = Modifier.fillMaxWidth(),
                    highlightLast = false
                )
                val rateLimits = snapshot.total.rateLimitHits
                if (rateLimits > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning, null,
                            tint = MaterialTheme.ledger.lampAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$rateLimits rate-limit hit(s) recorded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ledger.lampDeep
                        )
                    }
                }
            }
        }
    }
}

// ── Provider health ────────────────────────────────────────────────────────

@Composable
private fun ProviderHealthSection(uiState: CloudUsageDashboardViewModel.UiState) {
    val providers = uiState.settings.providers
    if (providers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Provider Health")
        providers.forEach { provider ->
            val health = uiState.health[provider.id]
            val stats = uiState.snapshot?.perProvider?.find { it.providerId == provider.id }
            CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HealthDot(health, provider.enabled)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            provider.name.ifBlank { provider.id },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.ledger.deskPaper,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (provider.isDefault) {
                            Text(
                                "default",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.ledger.lampDeep
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(
                            "Requests",
                            stats?.requests?.toString() ?: "0",
                            Modifier.weight(1f)
                        )
                        MetricTile(
                            "Success",
                            stats?.let { "${(it.successRate * 100).toInt()}%" } ?: "—",
                            Modifier.weight(1f)
                        )
                        MetricTile(
                            "Latency",
                            stats?.let { formatMs(it.avgLatencyMs) } ?: (health?.let { formatMs(it.latencyMs) } ?: "—"),
                            Modifier.weight(1f)
                        )
                    }
                    // Quota / rate-limit warnings.
                    provider.quota?.let { quota ->
                        val remainingRequests = quota.remainingRequests
                        val remainingTokens = quota.remainingTokens
                        val lowRequests = remainingRequests != null && remainingRequests <= 50
                        val lowTokens = remainingTokens != null && remainingTokens <= 1000
                        if (lowRequests || lowTokens) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    buildString {
                                        append("Approaching limits: ")
                                        if (remainingRequests != null) append("$remainingRequests requests left")
                                        if (lowRequests && lowTokens) append(", ")
                                        if (remainingTokens != null) append("$remainingTokens tokens left")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.ledger.lampDeep
                                )
                            }
                        }
                    }
                    if (!provider.enabled) {
                        Text("Provider disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskInkFaint)
                    } else if (provider.lastError.isNotBlank()) {
                        Text(
                            "Last error: ${provider.lastError.take(100)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ledger.emberRed,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthDot(health: CloudHealth?, enabled: Boolean) {
    val color = when {
        !enabled -> MaterialTheme.ledger.deskInkFaint
        health == null -> MaterialTheme.ledger.deskInkFaint
        health.ready || health.alive -> MaterialTheme.ledger.revolutNeonEmerald
        health.reachable -> MaterialTheme.ledger.lampAmber
        else -> MaterialTheme.ledger.emberRed
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, shape = CircleShape)
    )
}

// ── Tool calling ───────────────────────────────────────────────────────────

@Composable
private fun ToolCallingSection(snapshot: CloudUsageSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Tool Calling")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Tool calls", snapshot.total.toolCalls.toString(), Modifier.weight(1f))
                    MetricTile("Today", snapshot.today.toolCalls.toString(), Modifier.weight(1f))
                    MetricTile(
                        "Per request",
                        if (snapshot.total.requests > 0) {
                            "%.1f".format(snapshot.total.toolCalls.toFloat() / snapshot.total.requests)
                        } else "0",
                        Modifier.weight(1f)
                    )
                }
                val toolHeavy = snapshot.recentRecords.filter { it.toolCallsCount > 0 }.take(5)
                if (toolHeavy.isNotEmpty()) {
                    Text("Recent tool activity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.ledger.deskInk)
                    toolHeavy.forEach { record ->
                        StatRow(
                            label = "${record.modelId} · ${formatTime(record.timestampMs)}",
                            value = "${record.toolCallsCount} call(s)"
                        )
                    }
                } else {
                    Text(
                        "No tool calls yet — ask for weather, a search, or an SMS in cloud mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInkFaint
                    )
                }
            }
        }
    }
}

// ── Cache performance ──────────────────────────────────────────────────────

@Composable
private fun CacheSection(uiState: CloudUsageDashboardViewModel.UiState) {
    val cache = uiState.cacheStats
    val totals = uiState.snapshot?.total ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Cache Performance")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Prompt cache hit rate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ledger.deskInk)
                    Text(
                        "${(cache.hitRate * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.ledger.lampDeep
                    )
                }
                UsageRatioBar(
                    fraction = cache.hitRate,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.ledger.lampAmber
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Hits", cache.hits.toString(), Modifier.weight(1f))
                    MetricTile("Misses", cache.misses.toString(), Modifier.weight(1f))
                    MetricTile("Invalidations", cache.invalidations.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Tokens saved", formatTokens(cache.savedTokens), Modifier.weight(1f))
                    MetricTile("Cost saved", CloudPricing.formatUsd(cache.estimatedCostSavedMicros), Modifier.weight(1f))
                    MetricTile("Latency saved", formatMs(cache.estimatedLatencySavedMs), Modifier.weight(1f))
                }
                if (cache.lastInvalidationReason.isNotBlank()) {
                    Text(
                        "Last invalidation: ${cache.lastInvalidationReason.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ledger.deskInkFaint
                    )
                }
            }
        }
    }
}

// ── Models ─────────────────────────────────────────────────────────────────

@Composable
private fun ModelsSection(models: List<CloudModelLifetimeStats>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Models")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                models.take(8).forEach { model ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.modelId,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.ledger.deskPaper,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${model.requests} req · ${formatTokens(model.totalTokens)} tokens · ${CloudPricing.formatUsd(model.estimatedCostMicros)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.ledger.deskInkFaint,
                                maxLines = 1
                            )
                        }
                        Text(
                            formatMs(model.avgLatencyMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.ledger.deskInk
                        )
                    }
                }
            }
        }
    }
}

// ── History ────────────────────────────────────────────────────────────────

@Composable
private fun HistorySection(
    uiState: CloudUsageDashboardViewModel.UiState,
    viewModel: CloudUsageDashboardViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Request History")
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleHistory() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (uiState.historyExpanded) "Hide details" else "Show detailed history",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.ledger.lampDeep
                    )
                    Text(
                        "${uiState.history.size} request(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.ledger.deskInkFaint
                    )
                }
                if (uiState.historyExpanded) {
                    if (uiState.history.isEmpty()) {
                        Text(
                            "No requests match the current filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.ledger.deskInkFaint
                        )
                    }
                    uiState.history.take(50).forEach { record ->
                        HistoryRow(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: CloudUsageRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.ledger.deskWalnutDeep, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (record.success) Icons.Filled.CheckCircle else Icons.Filled.Close,
            contentDescription = null,
            tint = if (record.success) MaterialTheme.ledger.revolutNeonEmerald else MaterialTheme.ledger.emberRed,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.modelId.ifBlank { record.kind.name.lowercase() },
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.ledger.deskPaper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(formatTime(record.timestampMs))
                    append(" · ${formatTokens(record.totalTokens)} tok")
                    if (record.latencyMs > 0) append(" · ${formatMs(record.latencyMs)}")
                    if (record.toolCallsCount > 0) append(" · ${record.toolCallsCount} tools")
                    if (record.cacheHit) append(" · cache hit")
                    if (record.usedFallbackProvider) append(" · fallback")
                    if (!record.success && record.errorKind != CloudErrorKind.NONE) append(" · ${record.errorKind.name.lowercase()}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.ledger.deskInkFaint,
                maxLines = 1
            )
        }
        Text(
            CloudPricing.formatUsd(record.estimatedCostMicros),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.ledger.deskInk
        )
    }
}

// ── Shared bits ────────────────────────────────────────────────────────────

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ledger.deskInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.ledger.deskPaper
        )
    }
}

private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000_000 -> "%.1fB".format(value / 1_000_000_000.0)
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

private fun formatMs(value: Long): String = when {
    value <= 0 -> "—"
    value < 1000 -> "${value}ms"
    else -> "%.1fs".format(value / 1000.0)
}

private fun formatTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
