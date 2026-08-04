package io.androllm.feature.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.models.Conversation
import io.androllm.core.models.MessageRole
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.SoftCyan
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.GenerationConfig
import io.androllm.feature.chat.export.ConversationExporter
import io.androllm.feature.chat.export.ConversationSharer
import io.androllm.feature.chat.export.ExportFormat
import io.androllm.feature.chat.ui.components.ComposeInputArea
import io.androllm.feature.chat.ui.components.MessageBubble
import io.androllm.feature.chat.ui.components.NewChatEmptyState
import io.androllm.feature.chat.ui.components.NoModelLoadedCard
import io.androllm.feature.chat.ui.components.SearchOverlay
import io.androllm.feature.chat.ui.components.TypingAndThinkingIndicator
import io.androllm.feature.chat.ui.drawer.ConversationDrawerContent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Cloud Intelligence Chat Screen with 6-Layer background, Markdown rendering,
 * floating input bar, streaming animations, and sampler options.
 */
@Composable
fun ChatScreen(
    navController: NavController,
    conversationId: String = "",
    onNavigateUp: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var inputMessageText by remember { mutableStateOf("") }
    var renameDialogOpen by remember { mutableStateOf<Conversation?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var editPromptMsgOpen by remember { mutableStateOf<ChatMessage?>(null) }
    var editPromptText by remember { mutableStateOf("") }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var debugDialogOpen by remember { mutableStateOf(false) }
    var samplerSheetOpen by remember { mutableStateOf(false) }

    val debugInfo by viewModel.debugInfo.collectAsStateWithLifecycle()
    val genConfig by viewModel.genConfig.collectAsStateWithLifecycle()

    val successState = uiState as? ChatUiState.Success

    LaunchedEffect(conversationId) {
        if (conversationId.isNotBlank()) {
            viewModel.loadConversation(conversationId)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawerContent(
                activeConversations = successState?.activeConversations ?: emptyList(),
                pinnedConversations = successState?.pinnedConversations ?: emptyList(),
                selectedConversationId = successState?.conversationId ?: "",
                currentModelName = (successState?.engineState as? EngineState.Ready)?.model?.id,
                ramUsageText = "3.2 GB",
                storageUsageText = "4.1 GB",
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    viewModel.createNewConversation()
                    scope.launch { drawerState.close() }
                },
                onPinToggle = { conv -> viewModel.togglePinConversation(conv) },
                onRenameChat = { conv ->
                    renameDialogOpen = conv
                    renameInputText = conv.title
                },
                onDuplicateChat = { conv -> viewModel.duplicateConversation(conv.id) },
                onDeleteChat = { conv -> viewModel.deleteConversation(conv.id) },
                onOpenSearch = {
                    viewModel.toggleSearch(true)
                    scope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    navController.navigate("settings")
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        CloudAtmosphericBackground {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    ChatTopBar(
                        conversationTitle = successState?.conversation?.title ?: "New Chat",
                        engineState = successState?.engineState ?: EngineState.Unloaded,
                        performanceStats = successState?.performanceStats,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSearch = { viewModel.toggleSearch(true) },
                        onRename = {
                            successState?.conversation?.let { conv ->
                                renameDialogOpen = conv
                                renameInputText = conv.title
                            }
                        },
                        onDuplicate = {
                            successState?.conversationId?.let { viewModel.duplicateConversation(it) }
                        },
                        onPinToggle = {
                            successState?.conversation?.let { viewModel.togglePinConversation(it) }
                        },
                        onExport = { exportDialogOpen = true },
                        onDebugInfo = {
                            viewModel.refreshDebugInfo()
                            debugDialogOpen = true
                        },
                        onOpenSampler = { samplerSheetOpen = true },
                        onDelete = {
                            successState?.conversationId?.let { viewModel.deleteConversation(it) }
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    val listState = rememberLazyListState()
                    val messages = successState?.messages ?: emptyList()
                    val streamingText = successState?.streamingText
                    val isGenerating = successState?.isGenerating == true

                    val isAtBottom by remember {
                        derivedStateOf {
                            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            val totalItemsCount = listState.layoutInfo.totalItemsCount
                            totalItemsCount == 0 || lastVisibleItemIndex >= totalItemsCount - 2
                        }
                    }

                    LaunchedEffect(messages.size, streamingText) {
                        if (isGenerating && (isAtBottom || successState?.userPreferences?.autoScroll == true)) {
                            val targetIndex = (messages.size + (if (!streamingText.isNullOrEmpty()) 1 else 0)).coerceAtLeast(1) - 1
                            if (targetIndex >= 0) {
                                listState.animateScrollToItem(targetIndex)
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (successState?.engineState is EngineState.Unloaded) {
                            NoModelLoadedCard(onNavigateToModels = { navController.navigate("models") })
                        }

                        if (messages.isEmpty() && streamingText.isNullOrEmpty() && !isGenerating) {
                            NewChatEmptyState(
                                onSuggestionClick = { suggestion ->
                                    viewModel.sendMessage(suggestion)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(messages, key = { it.id }) { msg ->
                                    MessageBubble(
                                        message = msg,
                                        markdownEnabled = successState?.userPreferences?.markdownEnabled ?: true,
                                        codeWrapping = successState?.userPreferences?.codeWrapping ?: false,
                                        isBookmarked = msg.isBookmarked,
                                        onRegenerate = { viewModel.regenerateLastResponse() },
                                        onEditPrompt = {
                                            editPromptMsgOpen = msg
                                            editPromptText = msg.content
                                        },
                                        onDelete = { viewModel.deleteMessage(msg.id) },
                                        onBookmarkToggle = { viewModel.toggleBookmarkMessage(msg.id, msg.isBookmarked) }
                                    )
                                }

                                if (isGenerating && !streamingText.isNullOrEmpty()) {
                                    item(key = "streaming_bubble") {
                                        MessageBubble(
                                            message = ChatMessage(
                                                id = "streaming",
                                                conversationId = successState?.conversationId ?: "",
                                                role = MessageRole.ASSISTANT,
                                                content = streamingText,
                                                timestamp = System.currentTimeMillis()
                                            ),
                                            isStreaming = true,
                                            markdownEnabled = successState?.userPreferences?.markdownEnabled ?: true,
                                            codeWrapping = successState?.userPreferences?.codeWrapping ?: false
                                        )
                                    }
                                }

                                if (isGenerating && streamingText.isNullOrEmpty()) {
                                    item(key = "thinking_indicator") {
                                        TypingAndThinkingIndicator()
                                    }
                                }
                            }
                        }

                        ComposeInputArea(
                            text = inputMessageText,
                            onTextChanged = { inputMessageText = it },
                            onSendMessage = { text ->
                                viewModel.sendMessage(text)
                                inputMessageText = ""
                            },
                            onStopGeneration = { viewModel.cancelGeneration() },
                            isGenerating = isGenerating
                        )
                    }

                    AnimatedVisibility(
                        visible = !isAtBottom && (messages.isNotEmpty() || isGenerating),
                        enter = fadeIn() + slideInVertically(initialOffsetY = { 40 }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { 40 }),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 90.dp, end = 20.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                scope.launch {
                                    val targetIndex = (messages.size + (if (!streamingText.isNullOrEmpty()) 1 else 0)).coerceAtLeast(1) - 1
                                    if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
                                }
                            },
                            containerColor = io.androllm.core.ui.theme.CloudGlassSurface,
                            contentColor = SoftCyan,
                            elevation = FloatingActionButtonDefaults.elevation(8.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom")
                        }
                    }
                }
            }
        }
    }

    if (successState?.isSearchOpen == true) {
        SearchOverlay(
            query = successState.searchQuery,
            onQueryChanged = { viewModel.updateSearchQuery(it) },
            matchingConversations = successState.activeConversations.filter { it.title.contains(successState.searchQuery, ignoreCase = true) },
            matchingMessages = emptyList(),
            onSelectConversation = { id ->
                viewModel.selectConversation(id)
                viewModel.toggleSearch(false)
            },
            onDismiss = { viewModel.toggleSearch(false) }
        )
    }

    renameDialogOpen?.let { conv ->
        AlertDialog(
            onDismissRequest = { renameDialogOpen = null },
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameInputText.isNotBlank()) {
                            viewModel.renameConversation(conv.id, renameInputText)
                        }
                        renameDialogOpen = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = null }) { Text("Cancel") }
            }
        )
    }

    editPromptMsgOpen?.let { msg ->
        AlertDialog(
            onDismissRequest = { editPromptMsgOpen = null },
            title = { Text("Edit Prompt") },
            text = {
                OutlinedTextField(
                    value = editPromptText,
                    onValueChange = { editPromptText = it },
                    maxLines = 4,
                    label = { Text("User Prompt") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editPromptText.isNotBlank()) {
                            viewModel.editUserPrompt(msg.id, editPromptText)
                        }
                        editPromptMsgOpen = null
                    }
                ) { Text("Submit & Regenerate") }
            },
            dismissButton = {
                TextButton(onClick = { editPromptMsgOpen = null }) { Text("Cancel") }
            }
        )
    }

    if (exportDialogOpen) {
        AlertDialog(
            onDismissRequest = { exportDialogOpen = false },
            title = { Text("Export Conversation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select format:")
                    TextButton(onClick = {
                        exportDialogOpen = false
                        val exported = ConversationExporter.export(successState?.conversation?.title ?: "Chat", successState?.messages?.map { it.toCoreMessage() } ?: emptyList(), ExportFormat.MARKDOWN)
                        ConversationSharer.shareText(context, exported, "Export Markdown")
                    }) { Text("Markdown (.md)") }

                    TextButton(onClick = {
                        exportDialogOpen = false
                        val exported = ConversationExporter.export(successState?.conversation?.title ?: "Chat", successState?.messages?.map { it.toCoreMessage() } ?: emptyList(), ExportFormat.PLAIN_TEXT)
                        ConversationSharer.shareText(context, exported, "Export Text")
                    }) { Text("Plain Text (.txt)") }

                    TextButton(onClick = {
                        exportDialogOpen = false
                        val exported = ConversationExporter.export(successState?.conversation?.title ?: "Chat", successState?.messages?.map { it.toCoreMessage() } ?: emptyList(), ExportFormat.JSON)
                        ConversationSharer.shareText(context, exported, "Export JSON")
                    }) { Text("JSON (.json)") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { exportDialogOpen = false }) { Text("Close") }
            }
        )
    }

    if (debugDialogOpen) {
        AlertDialog(
            onDismissRequest = { debugDialogOpen = false },
            title = { Text("Engine Debug") },
            text = {
                val info = debugInfo
                if (info == null) {
                    Text("No debug data available.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DebugRow("Model", info.desc)
                        DebugRow("General", info.generalName)
                        DebugRow("Architecture", info.architecture)
                        DebugRow("Tokenizer", info.tokenizerModel)
                        DebugRow("Backend", info.backend)
                        DebugRow("GPU", info.gpuName)
                        DebugRow("Driver", info.gpuDriverVersion)
                        DebugRow("GPU Layers", "${info.gpuLayers}/${info.totalLayers}")
                        DebugRow("Context", "${info.nCtx}/${info.nCtxTrain}")
                        DebugRow("Batch", "${info.nBatch}/${info.nUbatch}")
                        DebugRow("Threads", info.nThreads.toString())
                        DebugRow("Vocab", info.nVocab.toString())
                        DebugRow("KV Cache", info.kvType)
                        DebugRow("Flash Attn", info.flashAttn)
                        DebugRow("Quantization", info.quantization)
                        DebugRow("Sampler", info.sampler)
                        DebugRow("Template", if (info.templateReady) "ready" else "FAILED: ${info.templateError}")
                        DebugRow("BOS/EOS", "${info.bosToken.replace("\n", "\\n")} / ${info.eosToken.replace("\n", "\\n")}")
                        DebugRow("add_bos/add_eos", "${info.addBos}/${info.addEos}")
                        DebugRow("First token", "${info.firstTokenMs} ms")
                        DebugRow("Stop reason", info.stopReason)
                        DebugRow("Prompt tokens", info.promptTokenIds.joinToString(" "))
                        DebugRow("Generated tokens", info.generatedTokenIds.joinToString(" "))
                        DebugRow("GPU verified", info.gpuInferenceVerified.toString())
                        DebugRow("Model size", "%.1f MB".format(info.modelSizeBytes / (1024.0 * 1024.0)))
                        DebugRow("Context size", "%.1f MB".format(info.contextSizeBytes / (1024.0 * 1024.0)))
                        DebugRow("Peak RAM", "%.1f MB".format(info.peakMemoryBytes / (1024.0 * 1024.0)))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { debugDialogOpen = false }) { Text("Close") }
            }
        )
    }

    if (samplerSheetOpen) {
        SamplerSettingsSheet(
            config = genConfig,
            onConfigChange = viewModel::updateGenConfig,
            onDismiss = { samplerSheetOpen = false }
        )
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MoonSilver.copy(alpha = 0.6f),
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = CloudWhite,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun SamplerSettingsSheet(
    config: GenerationConfig,
    onConfigChange: (GenerationConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Sampler Settings", style = MaterialTheme.typography.titleLarge)
            Text(
                "Applied to the next message. Defaults mirror llama.cpp.",
                style = MaterialTheme.typography.bodySmall,
                color = MoonSilver.copy(alpha = 0.7f)
            )

            SamplerSlider(
                label = "Max tokens",
                value = config.maxTokens.toFloat(),
                range = 16f..4096f,
                onValueChange = { onConfigChange(config.copy(maxTokens = it.roundToInt())) },
                format = { "%.0f".format(it) }
            )
            SamplerSlider(
                label = "Temperature",
                value = config.temperature,
                range = 0f..2f,
                onValueChange = { onConfigChange(config.copy(temperature = it)) }
            )
            SamplerSlider(
                label = "Top-P",
                value = config.topP,
                range = 0.05f..1f,
                onValueChange = { onConfigChange(config.copy(topP = it)) }
            )
            SamplerSlider(
                label = "Top-K",
                value = config.topK.toFloat(),
                range = 1f..100f,
                onValueChange = { onConfigChange(config.copy(topK = it.roundToInt())) },
                format = { "%.0f".format(it) }
            )
            SamplerSlider(
                label = "Min-P",
                value = config.minP,
                range = 0f..1f,
                onValueChange = { onConfigChange(config.copy(minP = it)) }
            )
            SamplerSlider(
                label = "Repetition penalty",
                value = config.repetitionPenalty,
                range = 1f..2f,
                onValueChange = { onConfigChange(config.copy(repetitionPenalty = it)) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reuse KV cache", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = config.reuseKvCache,
                    onCheckedChange = { onConfigChange(config.copy(reuseKvCache = it)) }
                )
            }

            TextButton(onClick = { onConfigChange(GenerationConfig()) }) {
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
private fun SamplerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    format: (Float) -> String = { "%.2f".format(it) }
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MoonSilver.copy(alpha = 0.7f)
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun ChatTopBar(
    conversationTitle: String,
    engineState: EngineState,
    performanceStats: io.androllm.engine.models.EngineStats?,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onPinToggle: () -> Unit,
    onExport: () -> Unit,
    onDebugInfo: () -> Unit,
    onOpenSampler: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = conversationTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = CloudWhite
                    )
                )

                val statusLabel = when (engineState) {
                    is EngineState.Ready -> performanceStats?.tokensPerSecond?.let { "%.1f tok/s".format(it) } ?: "Vulkan Ready"
                    is EngineState.Loading -> "Loading: ${engineState.stage}"
                    is EngineState.WarmingUp -> "Warming Up: ${engineState.step}"
                    is EngineState.Generating -> "Generating tokens..."
                    EngineState.Unloading -> "Unloading..."
                    is EngineState.Failed -> "Model Error"
                    EngineState.Unloaded -> "Offline AI"
                }

                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SoftCyan
                    )
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Open Drawer", tint = CloudWhite)
            }
        },
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = CloudWhite)
            }

            IconButton(onClick = onOpenSampler) {
                Icon(Icons.Default.Tune, contentDescription = "Sampler settings", tint = CloudWhite)
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = CloudWhite)
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename Chat") },
                        onClick = { menuExpanded = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Pin Chat") },
                        onClick = { menuExpanded = false; onPinToggle() },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate Chat") },
                        onClick = { menuExpanded = false; onDuplicate() },
                        leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        onClick = { menuExpanded = false; onExport() },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Debug Info") },
                        onClick = { menuExpanded = false; onDebugInfo() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Chat", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

private fun ChatMessage.toCoreMessage(): io.androllm.core.models.Message = io.androllm.core.models.Message(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    isBookmarked = isBookmarked
)
