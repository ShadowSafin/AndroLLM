package io.androllm.feature.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.model.CloudProvider
import io.androllm.core.navigation.Routes
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.components.SectionHeader
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import io.androllm.core.ui.theme.ledger

/**
 * Cloud Providers management screen: add/edit/delete providers, test
 * connections, refresh model lists, and control cloud chat mode.
 */
@Composable
fun CloudProvidersScreen(
    navController: NavController,
    viewModel: CloudProvidersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val healthStatus by viewModel.healthStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var formState by remember { mutableStateOf<ProviderFormState?>(null) }
    var confirmDelete by remember { mutableStateOf<CloudProvider?>(null) }

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
                            text = "Cloud Providers",
                            color = MaterialTheme.ledger.deskPaper,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.ledger.deskPaper)
                        }
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.CLOUD_USAGE) }) {
                            Icon(
                                Icons.Filled.Insights,
                                contentDescription = "Usage dashboard",
                                tint = MaterialTheme.ledger.deskPaper
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { formState = ProviderFormState() },
                    containerColor = MaterialTheme.ledger.lampAmber,
                    contentColor = MaterialTheme.ledger.deskPaper
                ) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    CloudModeCard(
                        enabled = uiState.settings.enabled,
                        onToggle = { viewModel.toggleCloudMode() }
                    )
                }

                item {
                    SectionHeader(
                        title = "Providers",
                        subtitle = "Self-hosted or hosted LiteLLM proxies"
                    )
                }

                if (uiState.settings.providers.isEmpty()) {
                    item {
                        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(
                                    text = "No providers yet",
                                    color = MaterialTheme.ledger.deskPaper,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Add your LiteLLM proxy URL and master key to start. " +
                                        "Model routing (OpenAI, Anthropic, Gemini, Groq, OpenRouter, Ollama, ...) " +
                                        "happens server-side — one gateway, every provider.",
                                    color = MaterialTheme.ledger.deskInk,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.settings.providers, key = { it.id }) { provider ->
                        ProviderCard(
                            provider = provider,
                            health = healthStatus[provider.id],
                            isDefault = provider.id == uiState.settings.defaultProviderId,
                            isTesting = uiState.testingId == provider.id,
                            isRefreshing = uiState.refreshingId == provider.id,
                            onTest = { viewModel.testConnection(provider.id) },
                            onRefreshModels = { viewModel.refreshModels(provider.id) },
                            onSetDefault = { viewModel.setDefault(provider.id) },
                            onToggleEnabled = { viewModel.toggleEnabled(provider.id) },
                            onEdit = {
                                formState = ProviderFormState(
                                    providerId = provider.id,
                                    name = provider.name,
                                    baseUrl = provider.baseUrl,
                                    apiKeyHeader = provider.apiKeyHeader,
                                    headersText = provider.extraHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                                    description = provider.description,
                                    tagsText = provider.tags.joinToString(", ")
                                )
                            },
                            onDelete = { confirmDelete = provider },
                            onOpenModels = {
                                navController.navigate(Routes.cloudModels(provider.id))
                            }
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(64.dp))
                }
            }
        }
    }

    formState?.let { form ->
        ProviderFormDialog(
            state = form,
            onDismiss = { formState = null },
            onSave = { name, baseUrl, apiKey, apiKeyHeader, headersText, description, tagsText ->
                val headers = parseHeaders(headersText)
                val tags = tagsText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (form.providerId == null) {
                    viewModel.addProvider(name, baseUrl, apiKey, apiKeyHeader, headers, description, tags)
                } else {
                    viewModel.updateProvider(form.providerId, name, baseUrl, apiKey, apiKeyHeader, headers, description, tags)
                }
                formState = null
            }
        )
    }

    confirmDelete?.let { provider ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete '${provider.name}'?") },
            text = { Text("The provider configuration and its cached model list will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProvider(provider.id)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.ledger.emberRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CloudModeCard(enabled: Boolean, onToggle: () -> Unit) {
    CloudGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (enabled) MaterialTheme.ledger.lampAmber.copy(alpha = 0.18f) else MaterialTheme.ledger.deskHairline,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (enabled) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                    contentDescription = null,                        tint = if (enabled) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Cloud chat mode",
                    color = MaterialTheme.ledger.deskPaper,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Route chat through your LiteLLM proxy instead of the local GGUF engine",
                    color = MaterialTheme.ledger.deskInk,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: CloudProvider,
    health: CloudHealth?,
    isDefault: Boolean,
    isTesting: Boolean,
    isRefreshing: Boolean,
    onTest: () -> Unit,
    onRefreshModels: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenModels: () -> Unit
) {
    CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = if (provider.enabled) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInk,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        color = MaterialTheme.ledger.deskPaper,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = provider.baseUrl,
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                StatusChip(enabled = provider.enabled)
                Spacer(Modifier.width(6.dp))
                HealthChip(health = health, provider = provider)
            }

            // Connection status line
            Row(verticalAlignment = Alignment.CenterVertically) {
                health?.takeIf { it.latencyMs > 0 }?.let { h ->
                    Text(
                        text = "${h.latencyMs} ms",
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(10.dp))
                }
                provider.latencyMs.takeIf { it > 0 && health == null }?.let { latency ->
                    Text(
                        text = "$latency ms",
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(10.dp))
                }
                provider.quota?.remainingRequests?.let { remaining ->
                    Text(
                        text = "quota: $remaining reqs",
                        color = if (remaining < 5) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(10.dp))
                }
                provider.modelIds.size.takeIf { it > 0 }?.let { count ->
                    Text(
                        text = "$count models",
                        color = MaterialTheme.ledger.deskInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.width(10.dp))
                // Tool-calling support: native OpenAI-compatible function
                // calling through the proxy, plus text-fallback parsing for
                // models that emit tool calls inside the answer text.
                Text(
                    text = "✓ tools",
                    color = MaterialTheme.ledger.revolutNeonEmerald,
                    style = MaterialTheme.typography.labelMedium
                )
                if (isDefault) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "DEFAULT",
                        color = MaterialTheme.ledger.lampAmber,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            provider.lastError.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = "⚠ $error",
                    color = MaterialTheme.ledger.emberRed,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }

            // Actions — FlowRow keeps every action (incl. Delete) visible on
            // narrow screens instead of clipping the row.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionButton(
                    icon = { if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.ledger.deskPaper,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    } },
                    label = "Test",
                    onClick = onTest,
                    enabled = !isTesting
                )
                ActionButton(
                    icon = { if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.ledger.deskPaper,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    } },
                    label = "Models",
                    onClick = onOpenModels,
                    enabled = !isRefreshing
                )
                ActionButton(
                    icon = { Icon(Icons.Filled.StarBorder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = if (isDefault) "Default" else "Set default",
                    onClick = onSetDefault,
                    enabled = !isDefault
                )
                ActionButton(
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = "Edit",
                    onClick = onEdit
                )
                ActionButton(
                    icon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = "Delete",
                    onClick = onDelete,
                    destructive = true
                )
            }

            provider.description.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    color = MaterialTheme.ledger.deskInk,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StatusChip(enabled: Boolean) {
    val (bg, fg, label) = if (enabled) {
        Triple(MaterialTheme.ledger.lampAmber.copy(alpha = 0.18f), MaterialTheme.ledger.lampGlow, "ON")
    } else {
        Triple(MaterialTheme.ledger.deskHairline, MaterialTheme.ledger.deskInk, "OFF")
    }
    Box(
        modifier = Modifier
            .background(bg, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Live reachability chip fed by [ProviderHealthMonitor]. LiteLLM proxies
 * (which implement /health/liveliness + /health/readiness) show
 * LIVE/DEGRADED/DOWN; OpenAI-compatible routers without those endpoints show
 * REACHABLE whenever the server answered at all, so a working router is not
 * reported as broken.
 */
@Composable
private fun HealthChip(health: CloudHealth?, provider: CloudProvider) {
    val (bg, fg, label) = when {
        health == null -> Triple(MaterialTheme.ledger.deskHairline, MaterialTheme.ledger.deskInk, "—")
        health.supportsHealthEndpoints -> when {
            health.alive && health.ready -> Triple(MaterialTheme.ledger.lampGlow.copy(alpha = 0.15f), MaterialTheme.ledger.lampGlow, "LIVE")
            health.alive -> Triple(MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f), MaterialTheme.ledger.lampAmber, "DEGRADED")
            else -> Triple(MaterialTheme.ledger.emberRed.copy(alpha = 0.15f), MaterialTheme.ledger.emberRed, "DOWN")
        }
        health.reachable -> Triple(MaterialTheme.ledger.lampGlow.copy(alpha = 0.15f), MaterialTheme.ledger.lampGlow, "REACHABLE")
        else -> Triple(MaterialTheme.ledger.emberRed.copy(alpha = 0.15f), MaterialTheme.ledger.emberRed, "DOWN")
    }
    Box(
        modifier = Modifier
            .background(bg, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ActionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) MaterialTheme.ledger.emberRed.copy(alpha = 0.16f) else MaterialTheme.ledger.deskHairline,
            contentColor = if (destructive) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskPaper,
            disabledContainerColor = MaterialTheme.ledger.deskHairline.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.ledger.deskInkFaint
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(5.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Form state shared between add and edit modes. */
data class ProviderFormState(
    val providerId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiKeyHeader: String = "Authorization",
    val headersText: String = "",
    val description: String = "",
    val tagsText: String = ""
)

@Composable
private fun ProviderFormDialog(
    state: ProviderFormState,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String, apiKeyHeader: String, headersText: String, description: String, tagsText: String) -> Unit
) {
    var name by remember { mutableStateOf(state.name) }
    var baseUrl by remember { mutableStateOf(state.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyHeader by remember { mutableStateOf(state.apiKeyHeader) }
    var headersText by remember { mutableStateOf(state.headersText) }
    var description by remember { mutableStateOf(state.description) }
    var tagsText by remember { mutableStateOf(state.tagsText) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.providerId == null) "Add Provider" else "Edit Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("My LiteLLM proxy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("LiteLLM URL") },
                    placeholder = { Text("https://my-proxy.example.com") },
                    supportingText = { Text("Base URL of the proxy — /v1 and /health are resolved automatically") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (state.providerId == null) "API key" else "API key (blank = keep current)") },
                    placeholder = { Text(if (state.providerId == null) "sk-..." else "••••••••") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKeyHeader,
                    onValueChange = { apiKeyHeader = it },
                    label = { Text("Auth header name") },
                    placeholder = { Text("Authorization") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text("Extra headers (one per line)") },
                    placeholder = { Text("X-API-Key: abc123") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Tags (comma separated)") },
                    placeholder = { Text("self-hosted, test") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(text = it, color = MaterialTheme.ledger.emberRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "Name is required"
                    !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
                        error = "URL must start with http:// or https://"
                    else -> onSave(name, baseUrl.trim(), apiKey, apiKeyHeader, headersText, description, tagsText)
                }
            }) { Text("Save", color = MaterialTheme.ledger.lampAmber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
