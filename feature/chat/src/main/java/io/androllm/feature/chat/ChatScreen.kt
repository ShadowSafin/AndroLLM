package io.androllm.feature.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.androllm.core.models.Conversation
import io.androllm.core.models.MessageRole
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.utils.PermissionUtils
import io.androllm.engine.api.EngineState
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.GenerationConfig
import io.androllm.feature.chat.export.ConversationExporter
import io.androllm.feature.chat.export.ConversationSharer
import io.androllm.feature.chat.export.ExportFormat
import io.androllm.feature.chat.ui.components.ChatSkeletonLoading
import io.androllm.feature.chat.ui.components.ComposeInputArea
import io.androllm.feature.chat.ui.components.GenerationStatsPanel
import io.androllm.feature.chat.ui.components.MessageCard
import io.androllm.feature.chat.ui.components.NewChatEmptyState
import io.androllm.feature.chat.ui.components.NoModelLoadedCard
import io.androllm.feature.chat.ui.components.SearchOverlay
import io.androllm.feature.chat.ui.components.ToolConfirmationCard
import io.androllm.feature.chat.ui.components.ToolInvocationCards
import io.androllm.feature.chat.ui.components.TypingAndThinkingIndicator
import io.androllm.feature.chat.ui.drawer.ConversationDrawerContent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.androllm.core.ui.theme.ledger

/**
 * THE PARCHMENT LEDGER — Chat. Your correspondence in ink on paper: ruled
 * letter slips instead of bubbles, a parchment composer, one terracotta send,
 * and the ledger of context and tokens above the page.
 */
@Composable
fun ChatScreen(
    navController: NavController,
    conversationId: String = "",
    initialPrompt: String? = null,
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
    var multiSelectIds by remember { mutableStateOf(setOf<String>()) }
    var statsExpanded by remember { mutableStateOf(false) }
    // Tool action approved while its system permission dialog is up; confirmed
    // once the user answers the dialog (granted or denied — the tool re-checks
    // and reports clearly either way). rememberSaveable so an activity
    // recreation (e.g. rotation) mid-dialog still confirms the right action.
    var pendingApprovalId by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingApprovalId?.let { viewModel.confirmToolAction(it, true) }
        pendingApprovalId = null
    }

    val debugInfo by viewModel.debugInfo.collectAsStateWithLifecycle()
    val genConfig by viewModel.genConfig.collectAsStateWithLifecycle()

    val successState = uiState as? ChatUiState.Success

    LaunchedEffect(conversationId) {
        if (conversationId.isNotBlank()) {
            viewModel.loadConversation(conversationId)
        }
    }

    // One-shot prompt from the Prompt Library.
    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            viewModel.sendPromptFromLibrary(initialPrompt)
        }
    }

    // Real device metrics for the drawer (no fabricated values).
    val hardwareInfo = remember(context) { io.androllm.core.utils.DeviceInfoCollector.collectDeviceInfo(context) }
    val storageStats = remember(context) { io.androllm.core.utils.StorageUtils.getStorageStats(context) }
    val ramUsedGb = (hardwareInfo.totalRamBytes - hardwareInfo.availableRamBytes) / (1024.0 * 1024.0 * 1024.0)
    val storageUsedGb = storageStats.usedBytes / (1024.0 * 1024.0 * 1024.0)

    // Tablet (Expanded width): the conversation list docks permanently to the
    // left as a second pane; phones keep the modal drawer behind the hamburger.
    val isWide = LocalConfiguration.current.screenWidthDp >= 840

    val drawerContent: @Composable () -> Unit = {
        ConversationDrawerContent(
            activeConversations = successState?.activeConversations ?: emptyList(),
            pinnedConversations = successState?.pinnedConversations ?: emptyList(),
            selectedConversationId = successState?.conversationId ?: "",
            currentModelName = (successState?.engineState as? EngineState.Ready)?.model?.id,
            ramUsageText = String.format("%.1f GB", ramUsedGb),
            storageUsageText = String.format("%.1f GB", storageUsedGb),
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

    val chatBody: @Composable () -> Unit = {
        CloudAtmosphericBackground(reduceMotion = successState?.userPreferences?.reduceMotion == true) {
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
                        },
                        cloudMode = successState?.cloudMode == true,
                        cloudDefaultModel = successState?.cloudDefaultModel.orEmpty(),
                        onToggleCloudMode = { viewModel.toggleCloudMode() },
                        showDrawerButton = !isWide
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Custom chat wallpaper — a flat wash of the user's chosen
                    // color over the atmospheric ground. Empty = default.
                    val wallpaperHex = successState?.userPreferences?.chatWallpaper.orEmpty()
                    if (wallpaperHex.isNotBlank()) {
                        val wallpaperColor = runCatching { androidx.compose.ui.graphics.Color(wallpaperHex.toLong(16)) }
                            .getOrNull()
                        if (wallpaperColor != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(wallpaperColor.copy(alpha = 0.35f))
                            )
                        }
                    }

                    val listState = rememberLazyListState()
                    val messages = successState?.messages ?: emptyList()
                    val streamingText = successState?.streamingText
                    val isGenerating = successState?.isGenerating == true
                    val liveTokenCount = (successState?.generationState as? io.androllm.engine.api.GenerationState.Generating)?.generatedTokens ?: 0L
                    // Stable timestamp for the streaming bubble — a fresh
                    // System.currentTimeMillis() per token would recreate the
                    // time formatter on every ~16ms recomposition.
                    val streamingTimestamp = remember { System.currentTimeMillis() }

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

                    if (uiState is ChatUiState.Loading) {
                        ChatSkeletonLoading(modifier = Modifier.fillMaxSize())
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (successState?.engineState is EngineState.Unloaded && successState?.cloudMode != true) {
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
                                    // PERFORMANCE: stable per-item callbacks. Fresh
                                    // lambdas per recomposition would force every
                                    // visible bubble to recompose on every uiState
                                    // emission (each streamed token, both
                                    // post-generation writes) — re-running layout
                                    // + markdown measurement for messages that
                                    // have not changed. remember(msg.id) gives
                                    // each message ONE lambda instance, so
                                    // Compose can skip unchanged items.
                                    //
                                    // The lambdas must still observe the CURRENT
                                    // message: a Room echo (bookmark toggle,
                                    // prompt edit) delivers a new ChatMessage
                                    // with the same id, so reading fields via
                                    // rememberUpdatedState keeps the stable
                                    // closure fresh without ever re-creating it.
                                    val currentMsg by rememberUpdatedState(msg)
                                    val selectionActive = multiSelectIds.isNotEmpty()
                                    val onRegenerate = remember(msg.id) { { viewModel.regenerateLastResponse() } }
                                    val onEditPrompt = remember(msg.id) {
                                        {
                                            editPromptMsgOpen = currentMsg
                                            editPromptText = currentMsg.content
                                        }
                                    }
                                    val onDelete = remember(msg.id) { { viewModel.deleteMessage(currentMsg.id) } }
                                    val onBookmarkToggle = remember(msg.id) {
                                        { viewModel.toggleBookmarkMessage(currentMsg.id, currentMsg.isBookmarked) }
                                    }
                                    val onSelectToggle = remember(msg.id) {
                                        {
                                            multiSelectIds =
                                                if (currentMsg.id in multiSelectIds) multiSelectIds - currentMsg.id
                                                else multiSelectIds + currentMsg.id
                                        }
                                    }
                                    val onLongPress = remember(msg.id) {
                                        { multiSelectIds = multiSelectIds + currentMsg.id }
                                    }
                                    MessageCard(
                                        message = msg,
                                        showBadge = msg.id == messages.lastOrNull()?.id,
                                        markdownEnabled = successState?.userPreferences?.markdownEnabled ?: true,
                                        codeWrapping = successState?.userPreferences?.codeWrapping ?: false,
                                        cloudMode = successState?.cloudMode == true,
                                        attachmentsEnabled = successState?.attachmentsSupported == true,
                                        messageAnimations = successState?.userPreferences?.messageAnimations ?: true,
                                        selected = currentMsg.id in multiSelectIds,
                                        selectionActive = selectionActive,
                                        onRegenerate = onRegenerate,
                                        onEditPrompt = onEditPrompt,
                                        onDelete = onDelete,
                                        onBookmarkToggle = onBookmarkToggle,
                                        onClick = onSelectToggle,
                                        onLongPress = onLongPress
                                    )
                                }

                                if (isGenerating && !streamingText.isNullOrEmpty()) {
                                    item(key = "streaming_bubble") {
                                        MessageCard(
                                            message = ChatMessage(
                                                id = "streaming",
                                                conversationId = successState?.conversationId ?: "",
                                                role = MessageRole.ASSISTANT,
                                                content = streamingText,
                                                timestamp = streamingTimestamp
                                            ),
                                            isStreaming = true,
                                            showBadge = true,
                                            markdownEnabled = successState?.userPreferences?.markdownEnabled ?: true,
                                            codeWrapping = successState?.userPreferences?.codeWrapping ?: false,
                                            cloudMode = successState?.cloudMode == true,
                                            attachmentsEnabled = successState?.attachmentsSupported == true,
                                            messageAnimations = false,
                                            onStop = { viewModel.cancelGeneration() }
                                        )
                                    }
                                }

                                // Tool activity chip: visible while the planner
                                // is deciding or tools are executing.
                                successState?.toolActivity?.let { activity ->
                                    item(key = "tool_activity") {
                                        TypingAndThinkingIndicator(
                                            cloudMode = successState?.cloudMode == true,
                                            statusText = activity
                                        )
                                    }
                                }

                                // Live tool-invocation cards: one expandable
                                // card per executed call, streaming its status
                                // (Running → Done/Failed) with the exact
                                // arguments and result available on tap.
                                if (successState?.toolEvents?.isNotEmpty() == true) {
                                    item(key = "tool_cards_${successState.toolEvents.hashCode()}") {
                                        ToolInvocationCards(
                                            toolEvents = successState.toolEvents,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                    }
                                }

                                // High-risk tool action awaiting approval.
                                successState?.pendingToolConfirmation?.let { confirmation ->
                                    item(key = "tool_confirmation") {
                                        ToolConfirmationCard(
                                            confirmation = confirmation,
                                            onApprove = {
                                                val missing = confirmation.requiredPermissions
                                                    .filterNot { PermissionUtils.hasPermission(context, it) }
                                                if (missing.isNotEmpty()) {
                                                    // The tool needs a runtime permission the user
                                                    // hasn't granted yet (e.g. SEND_SMS for "message
                                                    // mom"). Ask for it BEFORE confirming, otherwise
                                                    // approving would still send nothing.
                                                    pendingApprovalId = confirmation.id
                                                    permissionLauncher.launch(missing.toTypedArray())
                                                } else {
                                                    viewModel.confirmToolAction(confirmation.id, true)
                                                }
                                            },
                                            onDeny = { viewModel.confirmToolAction(confirmation.id, false) }
                                        )
                                    }
                                }

                                if (isGenerating && streamingText.isNullOrEmpty() && successState?.toolActivity == null) {
                                    item(key = "thinking_indicator") {
                                        TypingAndThinkingIndicator(
                                            cloudMode = successState?.cloudMode == true,
                                            statusText = when {
                                                successState?.cloudMode == true -> "Preparing cloud response…"
                                                successState?.isPreparing == true -> "Preparing local model…"
                                                else -> "Thinking…"
                                            }
                                        )
                                    }
                                }

                                val lastIsAssistant = messages.lastOrNull()?.let { it.role == MessageRole.ASSISTANT } == true
                                if (!isGenerating && lastIsAssistant && messages.isNotEmpty()) {
                                    item(key = "smart_replies") {
                                        SmartReplyChips(
                                            onSend = { chip -> viewModel.sendMessage(chip) }
                                        )
                                    }
                                }
                            }
                        }

                        GenerationStatsPanel(
                            stats = successState?.performanceStats,
                            contextLength = (successState?.engineState as? EngineState.Ready)?.model?.contextLength ?: 0,
                            usedTokens = (successState?.performanceStats?.promptTokens ?: 0L) +
                                if (isGenerating) liveTokenCount else (successState?.performanceStats?.generatedTokens ?: 0L),
                            isGenerating = isGenerating,
                            cloudMode = successState?.cloudMode == true,
                            model = (successState?.engineState as? EngineState.Ready)?.model,
                            liveTokenCount = liveTokenCount,
                            expanded = statsExpanded,
                            onToggleExpanded = { statsExpanded = !statsExpanded },
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        // Pending attachment chips above the composer (only when
                        // the active model supports attachments — local LiteRT
                        // models have no attachment pipeline and the feature
                        // simply does not exist).
                        if (successState?.attachmentsSupported == true) {
                            val pendingAttachments = successState.pendingAttachments
                            val attachmentsProcessing = successState.attachmentsProcessing
                            if (pendingAttachments.isNotEmpty() || attachmentsProcessing) {
                                PendingAttachmentChips(
                                    attachments = pendingAttachments,
                                    processing = attachmentsProcessing,
                                    onRemove = { id -> viewModel.removeAttachment(id) }
                                )
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
                            isGenerating = isGenerating,
                            onAttachFiles = { uris -> viewModel.attachFiles(uris) },
                            // Capability-driven: the paperclip only exists for
                            // cloud models. Local models get a clean composer
                            // with no gap and no attachment affordance.
                            showAttachButton = successState?.attachmentsSupported == true
                        )
                    }
                    }

                    if (multiSelectIds.isNotEmpty()) {
                        SelectionActionBar(
                            count = multiSelectIds.size,
                            onCopy = {
                                val text = messages
                                    .filter { it.id in multiSelectIds }
                                    .joinToString("\n\n---\n\n") { it.content }
                                copyTextToClipboard(context, text, "Copied ${multiSelectIds.size} messages")
                            },
                            onShare = {
                                val text = messages
                                    .filter { it.id in multiSelectIds }
                                    .joinToString("\n\n---\n\n") { it.content }
                                ConversationSharer.shareText(context, text, "Share messages")
                            },
                            onDelete = {
                                multiSelectIds.forEach { viewModel.deleteMessage(it) }
                                multiSelectIds = emptySet()
                            },
                            onClose = { multiSelectIds = emptySet() },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }

                    AnimatedVisibility(
                        visible = !isAtBottom && (messages.isNotEmpty() || isGenerating),
                        enter = fadeIn() + slideInVertically(initialOffsetY = { 40 }) + scaleIn(initialScale = 0.8f),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { 40 }) + scaleOut(targetScale = 0.8f),
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
                            containerColor = MaterialTheme.ledger.lampAmber,
                            contentColor = MaterialTheme.ledger.inkOnLamp,
                            elevation = FloatingActionButtonDefaults.elevation(2.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom")
                        }
                    }
                }
            }
        }
    }

    // Wide (Expanded window class): permanent side pane + chat. Narrow:
    // classic modal drawer over the same chat body.
    if (isWide) {
        PermanentNavigationDrawer(drawerContent = drawerContent) {
            chatBody()
        }
    } else {
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = drawerContent) {
            chatBody()
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

    // Cloud → local switch with pending attachments: confirm that switching
    // discards them (attachments are cloud-only).
    if (successState?.confirmCloudToLocalSwitch == true) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmSwitchToLocal(false) },
            title = { Text("Switch to local model?") },
            text = {
                Text("Attachments are only supported by cloud models. Switching to a local model will remove the current attachments.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSwitchToLocal(true) }) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmSwitchToLocal(false) }) { Text("Cancel") }
            }
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
                        DebugRow(
                            "Backend",
                            when (info.backend) {
                                "gpu" -> "LiteRT GPU"
                                "npu" -> "NPU"
                                "cpu" -> "CPU"
                                else -> info.backend
                            }
                        )
                        DebugRow("Delegate", info.delegate)
                        DebugRow("NPU", if (info.backend == "npu") info.npuAccelerator else "—")
                        DebugRow("NPU vendor", if (info.backend == "npu") info.npuVendor else "—")
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
                        DebugRow("Vulkan validation", info.vulkanValidationStatus)
                        DebugRow("Validation detail", info.vulkanValidationDetail.ifBlank { "—" })
                        DebugRow("Recovery count", info.recoveryCount.toString())
                        DebugRow("Last recovery", info.lastRecoveryReason.ifBlank { "—" })
                        DebugRow("CPU session", info.cpuSessionFallback.toString())
                        DebugRow("Vulkan ctx create", "${info.lastContextCreateMs} ms")
                        DebugRow("Vulkan cleanup", "${info.lastCleanupMs} ms")
                        DebugRow("Decodes", "${info.decodeCount} · avg ${info.decodeAvgMs} ms")
                        DebugRow("DeviceLost recovered", info.vulkanDeviceLostRecoveries.toString())
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
        io.androllm.feature.chat.ui.components.ModelParameterSheet(
            onDismissRequest = { samplerSheetOpen = false },
            initialTemperature = genConfig.temperature,
            initialTopP = genConfig.topP,
            initialMaxTokens = genConfig.maxTokens,
            onApplyParameters = { temp, topP, maxTokens, _ ->
                viewModel.updateGenConfig(
                    genConfig.copy(temperature = temp, topP = topP, maxTokens = maxTokens)
                )
            }
        )
    }
}

/**
 * Suggested follow-up chips rendered under the last completed assistant
 * message. Tapping one sends it as a new user turn.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartReplyChips(onSend: (String) -> Unit) {
    val suggestions = remember {
        listOf(
            "Explain this in more detail",
            "Give me a concrete example",
            "Summarise the key points",
            "What are the trade-offs?"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "FOLLOW UP",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.6.sp,
                color = MaterialTheme.ledger.deskInkFaint
            )
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.ledger.deskWalnutRaised.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
                    modifier = Modifier
                        .shadow(1.dp, RoundedCornerShape(999.dp))
                        .clickable { onSend(suggestion) }
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.deskInk,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** Floating bar with bulk actions while messages are selected. */
@Composable
private fun SelectionActionBar(
    count: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.ledger.deskWalnutRaised,
        border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline),
        shadowElevation = 4.dp,
        modifier = modifier.padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close selection",
                    tint = MaterialTheme.ledger.deskInk
                )
            }
            Text(
                text = if (count == 1) "1 selected" else "$count selected",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.ledger.deskInk
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy selected", tint = MaterialTheme.ledger.deskInk)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share selected", tint = MaterialTheme.ledger.deskInk)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.ledger.emberRed)
            }
        }
    }
}

private fun copyTextToClipboard(context: android.content.Context, text: String, toast: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Messages", text))
    android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.ledger.deskInk,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.ledger.deskPaper,
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
                color = MaterialTheme.ledger.deskInk
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
                color = MaterialTheme.ledger.deskInk
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
    onDelete: () -> Unit,
    cloudMode: Boolean = false,
    cloudDefaultModel: String = "",
    onToggleCloudMode: () -> Unit = {},
    showDrawerButton: Boolean = true
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = conversationTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.ledger.deskPaper
                    )
                )

                val statusLabel = if (cloudMode) {
                    if (cloudDefaultModel.isNotBlank()) "Cloud · $cloudDefaultModel" else "Cloud · no model"
                } else {
                    when (engineState) {
                        is EngineState.Ready -> performanceStats?.tokensPerSecond?.let { "${"%.1f".format(it)} tok/s" } ?: "Vulkan Ready"
                        is EngineState.Loading -> "Loading: ${engineState.stage}"
                        is EngineState.WarmingUp -> "Warming Up: ${engineState.step}"
                        is EngineState.Generating -> "Generating tokens..."
                        EngineState.Unloading -> "Unloading..."
                        is EngineState.Failed -> {
                            val msg = engineState.message
                            "Model Error: ${if (msg.length > 48) msg.take(48) + "…" else msg}"
                        }
                        EngineState.Unloaded -> "Offline AI"
                    }
                }

                Text(
                    text = statusLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.ledger.lampDeep
                    )
                )
            }
        },
        navigationIcon = {
            if (showDrawerButton) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Open Drawer", tint = MaterialTheme.ledger.deskPaper)
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleCloudMode) {
                Icon(
                    imageVector = if (cloudMode) Icons.Filled.CloudDone else Icons.Filled.Cloud,
                    contentDescription = if (cloudMode) "Switch to local GGUF" else "Switch to cloud (LiteLLM)",
                    tint = if (cloudMode) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskPaper
                )
            }

            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.ledger.deskPaper)
            }

            IconButton(onClick = onOpenSampler) {
                Icon(Icons.Default.Tune, contentDescription = "Sampler settings", tint = MaterialTheme.ledger.deskPaper)
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = MaterialTheme.ledger.deskPaper)
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
    isBookmarked = isBookmarked,
    origin = origin,
    attachmentsJson = attachmentsJson
)

/**
 * Pending attachment chips above the composer (cloud mode only). Each chip
 * shows the file name, size and status; tapping ✕ removes it before sending.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingAttachmentChips(
    attachments: List<io.androllm.core.attachments.model.ChatAttachment>,
    processing: Boolean,
    onRemove: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        attachments.forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.ledger.deskWalnutRaised.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = if (attachment.type == io.androllm.core.attachments.model.AttachmentType.IMAGE) "🖼" else "📄",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = attachment.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.ledger.deskPaper,
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                        Text(
                            text = when {
                                attachment.isFailed -> "Failed"
                                attachment.isReady -> io.androllm.core.attachments.model.ChatAttachment.formatSize(attachment.sizeBytes)
                                else -> "Processing…"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (attachment.isFailed) MaterialTheme.ledger.emberRed else MaterialTheme.ledger.deskInkFaint
                            )
                        )
                    }
                    IconButton(
                        onClick = { onRemove(attachment.id) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${attachment.name}",
                            tint = MaterialTheme.ledger.deskInkFaint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        if (processing) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.ledger.deskWalnutRaised.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, MaterialTheme.ledger.deskHairline)
            ) {
                Text(
                    text = "Processing…",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
