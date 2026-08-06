package io.androllm.feature.cloud

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.cloud.model.CloudCustomModel
import io.androllm.core.cloud.model.CloudModelProvider
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.DeskHairline
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.EmberRed
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampGlow
import java.util.Locale

/**
 * Model discovery screen for a cloud provider: browse cached models
 * (discovered + custom), refresh discovery, favorite, set the default chat
 * model, and manage custom LiteLLM models (own server/key/headers).
 */
@Composable
fun CloudModelsScreen(
    navController: NavController,
    viewModel: CloudModelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var customForm by remember { mutableStateOf<CustomModelFormState?>(null) }
    var confirmDelete by remember { mutableStateOf<CloudCustomModel?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val provider = uiState.provider
    val settings = uiState.settings
    val entries = remember(provider, settings) { viewModel.entriesFor(provider, settings) }

    CloudAtmosphericBackground(reduceMotion = false) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Cloud Models",
                                color = DeskPaper,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = provider?.name ?: "No provider",
                                color = DeskInk,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DeskPaper)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }, enabled = !uiState.refreshing) {
                            if (uiState.refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = LampGlow,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh models", tint = DeskPaper)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            floatingActionButton = {
                if (provider != null) {
                    FloatingActionButton(
                        onClick = { customForm = CustomModelFormState() },
                        containerColor = LampAmber,
                        contentColor = DeskPaper
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add custom model")
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    provider == null -> {
                        item {
                            CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Add a provider in Cloud Providers first, then come back here to browse its models.",
                                    color = DeskInk,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    entries.isEmpty() -> {
                        item {
                            CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text(
                                        text = "No models yet",
                                        color = DeskPaper,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Refresh (top right) to pull the model list from /v1/models, " +
                                            "or use the + button to add a custom LiteLLM model manually.",
                                        color = DeskInk,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        item {
                            Text(
                                text = "Tap a model to make it the chat default.",
                                color = DeskInk,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        items(entries, key = { it.model.id + it.model.providerId }) { entry ->
                            ModelRow(
                                model = entry.model,
                                custom = entry.custom,
                                onToggleFavorite = { viewModel.toggleFavorite(entry.model.id) },
                                onSetDefault = { viewModel.setDefaultModel(entry.model.id) },
                                onEditCustom = {
                                    val c = entry.custom ?: return@ModelRow
                                    customForm = CustomModelFormState(
                                        customModelId = c.id,
                                        modelName = c.modelName,
                                        modelId = c.modelId,
                                        apiBaseUrl = c.apiBaseUrl.orEmpty(),
                                        apiKeyHeader = c.apiKeyHeader,
                                        headersText = c.extraHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                                        description = c.description,
                                        tagsText = c.tags.joinToString(", ")
                                    )
                                },
                                onDeleteCustom = { entry.custom?.let { confirmDelete = it } }
                            )
                        }
                    }
                }
            }
        }
    }

    customForm?.let { form ->
        CustomModelFormDialog(
            state = form,
            onDismiss = { customForm = null },
            onSave = { modelName, modelId, apiBaseUrl, apiKey, apiKeyHeader, headersText, description, tagsText ->
                if (form.customModelId == null) {
                    viewModel.addCustomModel(modelName, modelId, apiBaseUrl, apiKey, apiKeyHeader, headersText, description, tagsText)
                } else {
                    viewModel.updateCustomModel(
                        form.customModelId, modelName, modelId, apiBaseUrl,
                        apiKey.ifBlank { null }, apiKeyHeader, headersText, description, tagsText
                    )
                }
                customForm = null
            }
        )
    }

    confirmDelete?.let { custom ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete '${custom.modelName}'?") },
            text = { Text("The custom model configuration (server, key, headers) will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomModel(custom.id)
                    confirmDelete = null
                }) { Text("Delete", color = EmberRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ModelRow(
    model: CloudModelProvider,
    custom: CloudCustomModel?,
    onToggleFavorite: () -> Unit,
    onSetDefault: () -> Unit,
    onEditCustom: () -> Unit,
    onDeleteCustom: () -> Unit
) {
    CloudGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSetDefault),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.label,
                        color = if (model.isDefault) LampGlow else DeskPaper,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (model.isCustom) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(LampAmber.copy(alpha = 0.15f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(text = "CUSTOM", color = LampGlow, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                val meta = buildList {
                    if (model.id != model.displayName) add(model.id)
                    formatContextWindow(model.contextWindow)?.let { add(it) }
                    model.tags.forEach { add("#$it") }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        color = DeskInk,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                model.description.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        color = DeskInk,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                }
                if (model.isDefault) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Default chat model",
                        color = LampAmber,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (model.isFavorite) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(LampAmber.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorite",
                        tint = LampAmber,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (model.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (model.isFavorite) "Unfavorite" else "Favorite",
                    tint = if (model.isFavorite) LampAmber else DeskInk
                )
            }
            if (custom != null) {
                IconButton(onClick = onEditCustom) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit custom model", tint = DeskInk)
                }
                IconButton(onClick = onDeleteCustom) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete custom model", tint = EmberRed)
                }
            }
        }
    }
}

/** Form state for the custom-model dialog (add + edit modes). */
data class CustomModelFormState(
    val customModelId: String? = null,
    val modelName: String = "",
    val modelId: String = "",
    val apiBaseUrl: String = "",
    val apiKey: String = "",
    val apiKeyHeader: String = "Authorization",
    val headersText: String = "",
    val description: String = "",
    val tagsText: String = ""
)

@Composable
private fun CustomModelFormDialog(
    state: CustomModelFormState,
    onDismiss: () -> Unit,
    onSave: (modelName: String, modelId: String, apiBaseUrl: String, apiKey: String, apiKeyHeader: String, headersText: String, description: String, tagsText: String) -> Unit
) {
    var modelName by remember { mutableStateOf(state.modelName) }
    var modelId by remember { mutableStateOf(state.modelId) }
    var apiBaseUrl by remember { mutableStateOf(state.apiBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyHeader by remember { mutableStateOf(state.apiKeyHeader) }
    var headersText by remember { mutableStateOf(state.headersText) }
    var description by remember { mutableStateOf(state.description) }
    var tagsText by remember { mutableStateOf(state.tagsText) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.customModelId == null) "Add Custom Model" else "Edit Custom Model") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("Model name") },
                    placeholder = { Text("My reasoning model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("LiteLLM model identifier") },
                    placeholder = { Text("openai/gpt-4o, deepseek/deepseek-r1, ...") },
                    supportingText = { Text("The prefixed id the proxy routes server-side") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { apiBaseUrl = it },
                    label = { Text("Optional LiteLLM server URL") },
                    placeholder = { Text("Blank = provider's server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (state.customModelId == null) "API key" else "API key (blank = keep current)") },
                    placeholder = { Text("Optional — blank uses the provider key") },
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
                    placeholder = { Text("fast, reasoning") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(text = it, color = EmberRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    modelName.isBlank() -> error = "Model name is required"
                    modelId.isBlank() -> error = "LiteLLM model identifier is required"
                    else -> onSave(modelName, modelId.trim(), apiBaseUrl.trim(), apiKey, apiKeyHeader, headersText, description, tagsText)
                }
            }) { Text("Save", color = LampAmber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatContextWindow(tokens: Long?): String? {
    if (tokens == null || tokens <= 0) return null
    return when {
        tokens >= 1_000_000 -> "${String.format(Locale.US, "%.1f", tokens / 1_000_000.0)}M tokens"
        tokens >= 1_000 -> "${tokens / 1_000}k tokens"
        else -> "$tokens tokens"
    }
}
