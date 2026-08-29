package io.androllm.feature.coding.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.CloudGlassCard
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.agent.CodingGate
import io.androllm.feature.coding.agent.CodingTaskMode
import io.androllm.feature.coding.environment.BackgroundServiceInfo
import io.androllm.feature.coding.tools.ChangeKind
import io.androllm.feature.coding.tools.PlanStepStatus

/**
 * The dedicated AI Agent Coding Chat — a production-grade, mobile-first workspace assistant.
 *
 * Hierarchy is now explicit:
 *  - sticky workspace badge + model + CLI status + preview status at the top
 *  - task flow indicator (analyzing → reading → editing → running → checking → preview → done)
 *  - chat transcript with premium tool cards (running/success/failure/retry/install)
 *  - collapsible premium panels (plan / preview / terminal / files)
 *  - services strip with live URLs
 *  - quick actions + panel toggles with generous touch targets
 *  - polished input bar
 *
 * Every state is visible: running command, success, failure, retry, installing,
 * waiting for confirmation, build running, preview ready/failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodingChatScreen(
    onBack: () -> Unit,
    onChangeWorkspace: () -> Unit,
    viewModel: CodingChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val terminal by viewModel.terminal.collectAsStateWithLifecycle()
    val installed by viewModel.installedAddons.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var input by remember { mutableStateOf("") }
    var showTerminal by remember { mutableStateOf(false) }
    var showFileTree by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var showCheckpoints by remember { mutableStateOf(false) }
    var showFileActivity by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }

    // Auto-open the plan the first time the agent creates one.
    var planAutoOpened by remember { mutableStateOf(false) }
    LaunchedEffect(state.plan) {
        if (!planAutoOpened && state.plan.isNotEmpty()) {
            showPlan = true
            planAutoOpened = true
        }
    }

    // Auto-open preview when detection says READY + autoOpened, and auto-refresh
    // the panel when refreshTick bumps (file edits / build output changed).
    // Also open on FAILED so startup logs are visible immediately.
    LaunchedEffect(state.preview.status, state.preview.autoOpened, state.preview.targetUrl) {
        if (state.preview.status == PreviewUiStatus.READY && state.preview.autoOpened) {
            showPreview = true
        }
        if (state.preview.status == PreviewUiStatus.FAILED) {
            showPreview = true
        }
    }

    val gate = state.gate

    CloudAtmosphericBackground {
        Scaffold(
            topBar = {
                CodingTopBar(
                    workspaceName = state.workspace?.name ?: "No workspace",
                    workspacePath = state.workspace?.shortPath ?: "",
                    modelLabel = state.modelLabel,
                    isGenerating = state.isGenerating,
                    previewStatus = state.preview.status,
                    previewTitle = state.preview.targetTitle,
                    onBack = onBack,
                    onChangeWorkspace = onChangeWorkspace,
                    onMarketplace = { viewModel.toggleMarketplace(true) },
                    onEnvironment = { viewModel.toggleEnvironment(true) }
                )
            },
            // The composer is PINNED as the scaffold's bottom bar: it can never be
            // covered by panels, respects the navigation bar, and always shows the
            // Stop button while a workflow is running.
            bottomBar = {
                if (gate == CodingGate.Ready) {
                    ComposerArea(
                        input = input,
                        isGenerating = state.isGenerating,
                        workspacePath = state.workspace?.shortPath ?: "",
                        installedCount = installed.size,
                        modeLabel = "${state.taskMode.emoji} ${state.taskMode.label}",
                        terminalOpen = showTerminal,
                        fileTreeOpen = showFileTree,
                        planOpen = showPlan,
                        previewOpen = showPreview,
                        previewReady = state.preview.status == PreviewUiStatus.READY,
                        onInput = { input = it },
                        onSend = {
                            viewModel.sendMessage(input)
                            input = ""
                        },
                        onCancel = { viewModel.cancelGeneration() },
                        onAction = { viewModel.sendQuickAction(it) },
                        onTerminal = { showTerminal = !showTerminal },
                        onFileTree = { viewModel.refreshFileTree(); showFileTree = !showFileTree },
                        onPlan = { showPlan = !showPlan },
                        onPreview = { showPreview = !showPreview },
                        onCheckpoints = {
                            if (!showCheckpoints) viewModel.refreshCheckpoints()
                            showCheckpoints = !showCheckpoints
                        },
                        onFileActivity = { showFileActivity = !showFileActivity },
                        onMode = { showModeDialog = true }
                    )
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            when (gate) {
                is CodingGate.NeedsCloud -> GatePanel(
                    padding = padding,
                    title = "Cloud model required",
                    message = gate.message
                )
                is CodingGate.NeedsWorkspace -> GatePanel(
                    padding = padding,
                    title = "Choose a workspace",
                    message = gate.message,
                    actionLabel = "Choose workspace",
                    onAction = onChangeWorkspace
                )
                CodingGate.Ready -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // ── Sticky workspace + status header (mobile-friendly, always visible) ──
                        WorkspaceStatusHeader(
                            workspaceName = state.workspace?.name ?: "",
                            workspacePath = state.workspace?.absolutePath ?: "",
                            modelLabel = state.modelLabel,
                            taskMode = state.taskMode,
                            isGenerating = state.isGenerating,
                            previewStatus = state.preview.status,
                            previewTitle = state.preview.targetTitle,
                            previewPhase = state.preview.phase,
                            toolActivity = state.toolActivity,
                            runningCommand = state.runningCommand,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )

                        // ── Task flow visibility (analyzing → done) ──
                        if (state.isGenerating || state.plan.isNotEmpty() || state.toolActivity != null) {
                            TaskFlowIndicator(
                                plan = state.plan,
                                toolActivity = state.toolActivity,
                                isGenerating = state.isGenerating,
                                previewStatus = state.preview.status,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        // ── Preview ready banner (server running, panel collapsed):
                        //    tapping opens the URL in the device's default browser. ──
                        if (state.preview.status == PreviewUiStatus.READY && !showPreview) {
                            val readyUrl = state.preview.targetUrl.orEmpty()
                            PreviewReadyBanner(
                                title = state.preview.targetTitle ?: "Preview ready",
                                url = readyUrl,
                                onOpen = { openInDefaultBrowser(context, readyUrl) },
                                onDismiss = { /* keep ready but hide banner until next scan */ },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        // ── Resume banner: when a saved task is waiting for the user. ──
                        state.pendingResumeTask?.let { task ->
                            ResumeTaskBanner(
                                task = task,
                                onResume = { viewModel.resumeTask(task) },
                                onDiscard = { viewModel.discardPendingTask(task) },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        // ── Plan approval card: when the agent has proposed a plan
                        //    and is waiting for the user to review it before coding. ──
                        state.pendingPlanApproval?.let { draft ->
                            PlanApprovalCard(
                                draft = draft,
                                onEditStep = { id, text -> viewModel.editPlanStep(id, text) },
                                onAddStep = { text -> viewModel.addPlanStep(text) },
                                onRemoveStep = { id -> viewModel.removePlanStep(id) },
                                onMoveStep = { id, delta -> viewModel.movePlanStep(id, delta) },
                                onApprove = { edited -> viewModel.approvePlan(edited) },
                                onReject = { viewModel.rejectPlan() },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        // ── Chat + panels split. Both regions are weighted, so the
                        //    layout can NEVER overflow and push the composer away —
                        //    the chat stays reachable while preview/plan/terminal run. ──
                        val anyPanelOpen = showPlan || showPreview || showTerminal || showFileTree || showCheckpoints || showFileActivity
                        MessageList(
                            messages = state.messages,
                            onRetry = { viewModel.retryCommand(it) },
                            modifier = Modifier.weight(if (anyPanelOpen) 1.05f else 1f)
                        )

                        if (anyPanelOpen) {
                            Column(
                                modifier = Modifier
                                    .weight(0.95f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (showPlan) {
                                    PlanPanel(
                                        plan = state.plan,
                                        onClose = { showPlan = false },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp)
                                    )
                                }
                                if (showPreview) {
                                    PreviewPanel(
                                        url = previewUrlFor(state, services),
                                        previewStatus = state.preview.status,
                                        previewTitle = state.preview.targetTitle,
                                        previewSuggestion = state.preview.suggestion,
                                        frameworkLabel = state.preview.framework,
                                        phase = state.preview.phase,
                                        canStartServer = state.preview.canStartServer,
                                        serverRunning = state.preview.serverServiceId != null &&
                                            services.any { it.id == state.preview.serverServiceId && it.running },
                                        serverLog = state.preview.serverLog,
                                        onClose = { showPreview = false },
                                        onRefresh = { viewModel.refreshPreview() },
                                        onStartServer = { viewModel.startPreviewServer("panel") },
                                        onStopServer = { viewModel.stopPreviewServer() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp)
                                    )
                                }
                                if (showTerminal) {
                                    TerminalPanel(
                                        lines = terminal,
                                        runningCommand = state.runningCommand,
                                        onCancel = { viewModel.cancelCommand() },
                                        onClear = { viewModel.clearTerminal() },
                                        onClose = { showTerminal = false },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 280.dp)
                                            .padding(horizontal = 12.dp)
                                    )
                                }
                                if (showFileTree) {
                                    FileTreePanel(
                                        tree = state.fileTree,
                                        onClose = { showFileTree = false },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 280.dp)
                                            .padding(horizontal = 12.dp)
                                    )
                                }
                                if (showCheckpoints) {
                                    CheckpointsPanel(
                                        checkpoints = state.checkpoints,
                                        onClose = { showCheckpoints = false },
                                        onCreate = { name -> viewModel.createCheckpoint(name) },
                                        onRestore = { id -> viewModel.restoreCheckpoint(id) },
                                        onDelete = { id -> viewModel.deleteCheckpoint(id) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp)
                                    )
                                }
                                if (showFileActivity) {
                                    FileActivityPanel(
                                        activity = state.fileActivity,
                                        onClose = { showFileActivity = false },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp)
                                    )
                                }
                            }
                        }

                        // ── Running background services (dev servers): tapping a
                        //    URL opens it in the device's default browser. ──
                        if (services.isNotEmpty()) {
                            ServicesStrip(
                                services = services,
                                onStop = { viewModel.stopService(it) },
                                onOpenUrl = { url -> openInDefaultBrowser(context, url) },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // ── Persistent floating Stop: always reachable while a workflow
                    //    is active, regardless of panels or scrolling. ──
                    AnimatedVisibility(
                        visible = state.isGenerating,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 10.dp),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        FloatingStopButton(onClick = { viewModel.cancelGeneration() })
                    }
                }
            }
        }
    }

    // Error snackbar as a simple dialog.
    state.error?.let { err ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Coding agent") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text("OK") } }
        )
    }

    // Destructive-command confirmation.
    state.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.denyConfirmation() },
            title = { Text(pending.title) },
            text = {
                SelectionContainer {
                    Text(pending.detail, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.approveConfirmation() }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { viewModel.denyConfirmation() }) { Text("Cancel") } }
        )
    }

    // Missing-addon install prompt.
    state.pendingAddonInstall?.let { addonId ->
        val pkg = io.androllm.feature.coding.environment.MarketplaceCatalog.find(addonId)
        AlertDialog(
            onDismissRequest = { viewModel.confirmAddonInstall(false) },
            title = { Text("Install ${pkg?.name ?: addonId}?") },
            text = { Text(pkg?.description ?: "This addon is required to run the command.") },
            confirmButton = { TextButton(onClick = { viewModel.confirmAddonInstall(true) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmAddonInstall(false) }) { Text("Not now") } }
        )
    }

    // Major file change awaiting diff review (approve/reject before apply).
    state.pendingEditReview?.let { review ->
        EditReviewDialog(
            review = review,
            onApprove = { viewModel.approveEditReview() },
            onReject = { viewModel.rejectEditReview() }
        )
    }

    // Task-mode selector.
    if (showModeDialog) {
        TaskModeDialog(
            current = state.taskMode,
            onSelect = { mode ->
                viewModel.setTaskMode(mode)
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false }
        )
    }

    // Marketplace + environment sheets.
    if (state.showMarketplace) {
        MarketplaceSheet(
            onDismiss = { viewModel.toggleMarketplace(false) },
            onInstall = { viewModel.installAddon(it) },
            onRetry = { viewModel.retryInstall(it) },
            onUninstall = { viewModel.uninstallAddon(it) }
        )
    }
    if (state.showEnvironment) {
        EnvironmentSheet(
            workspacePath = state.workspace?.absolutePath ?: "",
            installed = installed,
            reviewMajorEdits = state.reviewMajorEdits,
            onReviewMajorEdits = { viewModel.setReviewMajorEdits(it) },
            onDismiss = { viewModel.toggleEnvironment(false) },
            onProvisionBase = { viewModel.provisionBase() }
        )
    }
}

/**
 * The URL the preview opens in the browser. Only server-backed URLs qualify:
 * the READY target (set exclusively while a server is running) or a running
 * background service's URL. No server → no preview URL.
 */
private fun previewUrlFor(state: CodingUiState, services: List<BackgroundServiceInfo>): String {
    if (state.preview.status == PreviewUiStatus.READY && !state.preview.targetUrl.isNullOrBlank()) {
        return state.preview.targetUrl
    }
    return services.firstOrNull { it.running && it.urlOnDevice != null }?.urlOnDevice ?: ""
}

/**
 * The pinned composer area (Scaffold bottomBar): quick actions, panel toggles and
 * the input bar. Being the scaffold's bottom bar, it is measured FIRST and can
 * never be covered by panels or pushed off-screen; it also respects the system
 * navigation bar. The Stop button stays reachable for the whole workflow.
 */
@Composable
private fun ComposerArea(
    input: String,
    isGenerating: Boolean,
    workspacePath: String,
    installedCount: Int,
    modeLabel: String,
    terminalOpen: Boolean,
    fileTreeOpen: Boolean,
    planOpen: Boolean,
    previewOpen: Boolean,
    previewReady: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onAction: (QuickAction) -> Unit,
    onTerminal: () -> Unit,
    onFileTree: () -> Unit,
    onPlan: () -> Unit,
    onPreview: () -> Unit,
    onCheckpoints: () -> Unit,
    onFileActivity: () -> Unit,
    onMode: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.ledger.deskNightRaised.copy(alpha = 0.92f))
            .navigationBarsPadding()
            .padding(top = 6.dp)
    ) {
        QuickActionsRow(
            enabled = !isGenerating,
            onAction = onAction,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        PanelToggleRow(
            workspacePath = workspacePath,
            installedCount = installedCount,
            modeLabel = modeLabel,
            terminalOpen = terminalOpen,
            fileTreeOpen = fileTreeOpen,
            planOpen = planOpen,
            previewOpen = previewOpen,
            previewReady = previewReady,
            checkpointsOpen = false,
            fileActivityOpen = false,
            onTerminal = onTerminal,
            onFileTree = onFileTree,
            onPlan = onPlan,
            onPreview = onPreview,
            onCheckpoints = onCheckpoints,
            onFileActivity = onFileActivity,
            onMode = onMode,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        InputBar(
            input = input,
            isGenerating = isGenerating,
            onInput = onInput,
            onSend = onSend,
            onCancel = onCancel,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/**
 * Persistent floating Stop button shown while a workflow is active. It overlays
 * the content (never displaces it), stays visible regardless of panel expansion
 * or scrolling, and cancels the agent loop + any running tool immediately.
 */
@Composable
private fun FloatingStopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFFEF4444),
        shadowElevation = 8.dp,
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Stop",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodingTopBar(
    workspaceName: String,
    workspacePath: String,
    modelLabel: String,
    isGenerating: Boolean,
    previewStatus: PreviewUiStatus,
    previewTitle: String?,
    onBack: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onMarketplace: () -> Unit,
    onEnvironment: () -> Unit
) {
    TopAppBar(
        modifier = Modifier.statusBarsPadding(),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.ledger.lampAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            workspaceName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.ledger.deskPaper
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                modelLabel,
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                                maxLines = 1
                            )
                            if (previewStatus == PreviewUiStatus.READY) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Color(0xFF34C759).copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "Preview ready",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = Color(0xFF1B7A2B),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.ledger.deskPaperDim)
            }
        },
        actions = {
            IconButton(onClick = onChangeWorkspace) {
                Icon(Icons.Filled.Folder, "Change workspace", tint = MaterialTheme.ledger.deskPaperDim)
            }
            IconButton(onClick = onMarketplace) {
                Icon(Icons.Filled.Extension, "Marketplace", tint = MaterialTheme.ledger.deskPaperDim)
            }
            IconButton(onClick = onEnvironment) {
                Icon(Icons.Filled.Memory, "Environment", tint = MaterialTheme.ledger.deskPaperDim)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

/**
 * Sticky workspace + status header — the single glanceable row the spec asks for:
 * workspace badge, model chip, CLI status dot, preview status pill, and live
 * tool activity. Compact on phones, readable, with strong touch targets.
 */
@Composable
private fun WorkspaceStatusHeader(
    workspaceName: String,
    workspacePath: String,
    modelLabel: String,
    taskMode: CodingTaskMode,
    isGenerating: Boolean,
    previewStatus: PreviewUiStatus,
    previewTitle: String?,
    previewPhase: String?,
    toolActivity: String?,
    runningCommand: String?,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                // Sticky workspace badge
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.ledger.lampAmber.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.ledger.lampAmber.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.ledger.lampAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            workspaceName.ifBlank { "Workspace" },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.ledger.lampDeep
                            ),
                            maxLines = 1
                        )
                    }
                }

                // Model chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.ledger.deskHairlineSoft
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Api,
                            contentDescription = null,
                            tint = MaterialTheme.ledger.deskInk,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            modelLabel.ifBlank { "Cloud model" },
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskPaper),
                            maxLines = 1
                        )
                    }
                }

                // Task mode chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.ledger.deskHairlineSoft
                ) {
                    Text(
                        "${taskMode.emoji} ${taskMode.label}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskPaper),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        maxLines = 1
                    )
                }

                // CLI status dot
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isGenerating) Color(0xFFF59E0B).copy(alpha = 0.15f) else Color(0xFF34C759).copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isGenerating) Color(0xFFF59E0B) else Color(0xFF34C759))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isGenerating) "Working…" else "CLI ready",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = if (isGenerating) Color(0xFF92400E) else Color(0xFF1B7A2B)
                            )
                        )
                    }
                }

                // Preview status pill (visible, not hidden)
                val (previewBg, previewDot, previewText, previewColor) = when (previewStatus) {
                    PreviewUiStatus.READY -> Quad(
                        Color(0xFF34C759).copy(alpha = 0.12f),
                        Color(0xFF34C759),
                        previewTitle ?: "Preview ready",
                        Color(0xFF1B7A2B)
                    )
                    PreviewUiStatus.SCANNING -> Quad(Color(0xFFF59E0B).copy(alpha = 0.12f), Color(0xFFF59E0B), "Scanning preview…", Color(0xFF92400E))
                    PreviewUiStatus.FAILED -> Quad(Color(0xFFEF4444).copy(alpha = 0.12f), Color(0xFFEF4444), "Preview failed", Color(0xFF991B1B))
                    PreviewUiStatus.NOT_AVAILABLE -> Quad(MaterialTheme.ledger.deskHairlineSoft, MaterialTheme.ledger.deskInkFaint, "No preview yet", MaterialTheme.ledger.deskInk)
                    PreviewUiStatus.IDLE -> Quad(MaterialTheme.ledger.deskHairlineSoft, MaterialTheme.ledger.deskInkFaint, "Preview idle", MaterialTheme.ledger.deskInk)
                }
                Surface(shape = RoundedCornerShape(50), color = previewBg) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(previewDot))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            previewText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = previewColor
                            ),
                            maxLines = 1
                        )
                    }
                }
            }

            // Second row: live activity — precise tool status, or the preview
            // lifecycle phase ("Starting local server…", "Waiting for localhost…").
            if (toolActivity != null || previewPhase != null || runningCommand != null) {
                val activityText = toolActivity
                    ?: previewPhase
                    ?: "Running ${runningCommand?.take(40)}…"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.ledger.lampAmber,
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        activityText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.ledger.lampDeep,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Task flow visibility — a horizontal stepper that maps the agent's visible flow:
 * analyzing workspace → reading files → editing code → running command → checking result → opening preview → done
 */
@Composable
private fun TaskFlowIndicator(
    plan: List<io.androllm.feature.coding.tools.PlanStep>,
    toolActivity: String?,
    isGenerating: Boolean,
    previewStatus: PreviewUiStatus,
    modifier: Modifier = Modifier
) {
    val steps = listOf(
        "Analyzing" to Icons.Filled.Search,
        "Reading" to Icons.Filled.Description,
        "Editing" to Icons.Filled.Code,
        "Running" to Icons.Filled.Terminal,
        "Checking" to Icons.Filled.CheckCircle,
        "Preview" to Icons.Filled.Visibility,
        "Done" to Icons.Filled.AutoAwesome
    )

    // Heuristic current index from live signals
    val currentIdx = remember(plan, toolActivity, isGenerating, previewStatus) {
        when {
            previewStatus == PreviewUiStatus.READY -> 5
            plan.any { it.status == PlanStepStatus.DONE } && !isGenerating && toolActivity == null && previewStatus != PreviewUiStatus.READY -> 6
            toolActivity != null -> when {
                toolActivity.contains("Reading", ignoreCase = true) || toolActivity.contains("read_file", ignoreCase = true) -> 1
                toolActivity.contains("Writing", ignoreCase = true) || toolActivity.contains("Editing", ignoreCase = true) || toolActivity.contains("write_file", ignoreCase = true) -> 2
                toolActivity.contains("Running", ignoreCase = true) || toolActivity.contains("run_command", ignoreCase = true) -> 3
                toolActivity.contains("Checking", ignoreCase = true) || toolActivity.contains("test", ignoreCase = true) || toolActivity.contains("build", ignoreCase = true) -> 4
                else -> 0
            }
            isGenerating -> 0
            plan.isNotEmpty() -> {
                val done = plan.count { it.status == PlanStepStatus.DONE }
                val total = plan.size
                when {
                    done == 0 -> 0
                    done < total -> 2
                    else -> 5
                }
            }
            else -> 0
        }
    }

    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TASK FLOW",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.lampDeep,
                        letterSpacing = 1.2.sp
                    )
                )
                Spacer(Modifier.width(8.dp))
                if (plan.isNotEmpty()) {
                    val done = plan.count { it.status == PlanStepStatus.DONE }
                    Text(
                        "$done/${plan.size} steps",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isGenerating) {
                    CircularProgressIndicator(
                        color = MaterialTheme.ledger.lampAmber,
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.forEachIndexed { idx, (label, icon) ->
                    val done = idx < currentIdx
                    val active = idx == currentIdx
                    val future = idx > currentIdx
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = when {
                            active -> MaterialTheme.ledger.lampAmber
                            done -> Color(0xFF34C759)
                            else -> MaterialTheme.ledger.deskHairlineSoft
                        },
                        border = if (active) BorderStroke(1.dp, MaterialTheme.ledger.lampDeep.copy(alpha = 0.3f)) else null,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = when {
                                    active || done -> Color.White
                                    else -> MaterialTheme.ledger.deskInkFaint
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                    color = when {
                                        active || done -> Color.White
                                        else -> MaterialTheme.ledger.deskInk
                                    }
                                )
                            )
                            if (done) {
                                Spacer(Modifier.width(4.dp))
                                Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (idx < steps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    if (idx < currentIdx) Color(0xFF34C759).copy(alpha = 0.6f)
                                    else MaterialTheme.ledger.deskHairline
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewReadyBanner(
    title: String,
    url: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    CloudGlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34C759).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Visibility, contentDescription = null, tint = Color(0xFF1B7A2B), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Preview Ready",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B7A2B)
                    )
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.ledger.deskInk),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (url.isNotBlank()) {
                    Text(
                        url,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.lampDeep, fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            CloudCapsuleButton(text = "Open", onClick = onOpen)
        }
    }
}

@Composable
private fun GatePanel(
    padding: PaddingValues,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        CloudGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.ledger.lampAmber,
                    modifier = Modifier.height(40.dp).width(40.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInk)
                )
                if (actionLabel != null) {
                    Spacer(Modifier.height(16.dp))
                    CloudCapsuleButton(text = actionLabel, onClick = onAction)
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<CodingChatMessage>,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastTextLen) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(messages, key = { it.id }) { msg -> MessageBubble(msg, onRetry) }
    }
}

@Composable
private fun MessageBubble(msg: CodingChatMessage, onRetry: (String) -> Unit) {
    when (msg.role) {
        CodingMessageRole.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                    .background(MaterialTheme.ledger.lampAmber)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                SelectionContainer {
                    Text(msg.text, color = MaterialTheme.ledger.inkOnLamp, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
                }
            }
        }
        CodingMessageRole.ASSISTANT -> Row(Modifier.fillMaxWidth()) {
            Card(
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnutRaised),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (msg.text.isEmpty() && msg.isStreaming) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = MaterialTheme.ledger.lampAmber,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking…", style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.ledger.deskInk))
                        }
                    } else {
                        SelectionContainer {
                            Text(msg.text, color = MaterialTheme.ledger.deskPaper, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
                        }
                    }
                }
            }
        }
        CodingMessageRole.TOOL -> {
            val lower = msg.text.lowercase()
            val isFailure = lower.contains("❌") || lower.contains("failed") || lower.contains("error") || msg.failedCommand != null
            val isSuccess = !isFailure && !msg.isStreaming
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        msg.isStreaming -> MaterialTheme.ledger.lampAmber.copy(alpha = 0.08f)
                        isFailure -> Color(0xFFFEF2F2)
                        isSuccess -> Color(0xFFF0FDF4)
                        else -> MaterialTheme.ledger.deskHairlineSoft
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    when {
                        msg.isStreaming -> MaterialTheme.ledger.lampAmber.copy(alpha = 0.25f)
                        isFailure -> Color(0xFFEF4444).copy(alpha = 0.2f)
                        isSuccess -> Color(0xFF34C759).copy(alpha = 0.2f)
                        else -> MaterialTheme.ledger.deskHairline
                    }
                )
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        msg.isStreaming -> MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f)
                                        isFailure -> Color(0xFFEF4444).copy(alpha = 0.15f)
                                        else -> Color(0xFF34C759).copy(alpha = 0.15f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    msg.toolName == "run_command" -> Icons.Filled.Terminal
                                    msg.toolName == "read_file" -> Icons.Filled.Description
                                    msg.toolName in listOf("write_file", "edit_file", "replace_text") -> Icons.Filled.Code
                                    msg.toolName == "grep" -> Icons.Filled.Search
                                    else -> Icons.Filled.Construction
                                },
                                contentDescription = null,
                                tint = when {
                                    msg.isStreaming -> MaterialTheme.ledger.lampAmber
                                    isFailure -> Color(0xFFEF4444)
                                    else -> Color(0xFF34C759)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                msg.toolName ?: "tool",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.ledger.lampDeep,
                                    letterSpacing = 0.6.sp
                                )
                            )
                            Text(
                                firstLineOf(msg.text).ifBlank { msg.toolName ?: "tool" },
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.ledger.deskPaper
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (msg.isStreaming) {
                            CircularProgressIndicator(
                                color = MaterialTheme.ledger.lampDeep,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else if (isFailure) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = "Failed", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = Color(0xFF34C759), modifier = Modifier.size(18.dp))
                        }
                    }
                    if (msg.text.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.ledger.deskHairline.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                msg.text.substringAfter("\n").ifBlank { msg.text },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.ledger.deskPaperDim
                            )
                        }
                    }
                    msg.diff?.let { diff ->
                        DiffBlock(diff = diff, modifier = Modifier.padding(top = 8.dp))
                    }
                    val failed = msg.failedCommand
                    if (failed != null && !msg.isStreaming) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CloudCapsuleButton(text = "↻ Retry", onClick = { onRetry(failed) })
                            Text(
                                "Failed: $failed",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF991B1B), fontFamily = FontFamily.Monospace),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.CenterVertically).weight(1f)
                            )
                        }
                    }
                }
            }
        }
        CodingMessageRole.SYSTEM -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.ledger.deskHairlineSoft
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInk),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun firstLineOf(text: String): String = text.lineSequence().firstOrNull()?.take(80) ?: ""

/**
 * A unified diff rendered with colored +/- lines. Long diffs are capped with a
 * "show more" expander so huge writes don't blow up the transcript.
 */
@Composable
private fun DiffBlock(diff: String, modifier: Modifier = Modifier) {
    val lines = remember(diff) { diff.lines() }
    var expanded by remember(diff) { mutableStateOf(false) }
    val cap = 30
    val shown = if (expanded || lines.size <= cap) lines else lines.take(cap)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Code, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text("Diff", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            Text("+${lines.count { it.startsWith("+") }}  −${lines.count { it.startsWith("-") }}", fontSize = 10.sp, color = Color(0xFF64748B), fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Column {
                shown.forEach { line -> DiffLineText(line) }
            }
        }
        if (!expanded && lines.size > cap) {
            Text(
                "Show ${lines.size - cap} more lines",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.ledger.lampDeep,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** One line of a unified diff, colored by kind. */
@Composable
private fun DiffLineText(line: String) {
    val color = when {
        line.startsWith("@@") -> Color(0xFF94A3B8)
        line.startsWith("+") -> Color(0xFF4ADE80)
        line.startsWith("-") -> Color(0xFFF87171)
        else -> Color(0xFFE2E8F0)
    }
    Text(
        line.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = color
    )
}

/**
 * Diff-review gate: a major file change is shown with its full diff and must be
 * approved before it is written to disk.
 */
@Composable
private fun EditReviewDialog(
    review: PendingEditReviewUi,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Dialog(onDismissRequest = onReject) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.ledger.deskNightRaised)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Review change",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.ledger.deskPaper
                    )
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${changeKindLabel(review.kind)} • ${review.path}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.ledger.lampDeep
            )
            Text(
                "+${review.added} −${review.removed} lines — approve to apply, reject to skip.",
                fontSize = 12.sp,
                color = MaterialTheme.ledger.deskInk
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F172A))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Column {
                        review.diff.lines().forEach { line -> DiffLineText(line) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReject) {
                    Text("Reject", color = MaterialTheme.ledger.emberRed, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                CloudCapsuleButton(text = "Approve", onClick = onApprove)
            }
        }
    }
}

private fun changeKindLabel(kind: ChangeKind): String = when (kind) {
    ChangeKind.CREATE -> "New file"
    ChangeKind.OVERWRITE -> "Overwrite"
    ChangeKind.EDIT -> "Edit"
}

/** Task-mode selector: tailors the agent's working method to the job. */
@Composable
private fun TaskModeDialog(
    current: CodingTaskMode,
    onSelect: (CodingTaskMode) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.ledger.deskNightRaised)
                .padding(8.dp)
        ) {
            Text(
                "Task mode",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                ),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
            CodingTaskMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mode.emoji, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (mode == current) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskPaper,
                            fontWeight = if (mode == current) FontWeight.Bold else FontWeight.Normal
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (mode == current) {
                        Text("✓", color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Persistent strip of running background services (dev servers): status dot,
 * command, tappable access URL (opens the in-app preview) and a stop button.
 * Now premium — each service is a card with clear hierarchy.
 */
@Composable
private fun ServicesStrip(
    services: List<BackgroundServiceInfo>,
    onStop: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "RUNNING SERVICES",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF34C759),
                    letterSpacing = 1.sp
                )
            )
            Spacer(Modifier.width(8.dp))
            Text("${services.size} active", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint))
        }
        services.forEach { svc ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskHairlineSoft),
                border = BorderStroke(1.dp, if (svc.running) Color(0xFF34C759).copy(alpha = 0.2f) else MaterialTheme.ledger.deskHairline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (svc.running) Color(0xFF34C759) else MaterialTheme.ledger.deskInkFaint)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            svc.command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.ledger.deskPaper
                        )
                        val url = svc.urlOnDevice ?: svc.urlNetwork
                        if (url != null) {
                            Text(
                                "▶ $url",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.ledger.lampDeep,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenUrl(url) }
                                    .padding(vertical = 2.dp)
                            )
                        } else {
                            Text(svc.statusLabel, fontSize = 11.sp, color = MaterialTheme.ledger.deskInk)
                        }
                    }
                    IconButton(onClick = { onStop(svc.id) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Stop service",
                            tint = MaterialTheme.ledger.emberRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** One-tap quick actions: each sends a templated prompt the agent adapts to the stack. */
@Composable
private fun QuickActionsRow(
    enabled: Boolean,
    onAction: (QuickAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "QUICK ACTIONS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.lampDeep,
                    letterSpacing = 1.sp
                )
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickAction.entries.forEach { action ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (enabled) MaterialTheme.ledger.deskWalnut else MaterialTheme.ledger.deskHairlineSoft,
                    shadowElevation = if (enabled) 2.dp else 0.dp,
                    border = BorderStroke(1.dp, if (enabled) MaterialTheme.ledger.deskHairline else Color.Transparent),
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = enabled) { onAction(action) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(action.emoji, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (enabled) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.deskInkFaint
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelToggleRow(
    workspacePath: String,
    installedCount: Int,
    modeLabel: String,
    terminalOpen: Boolean,
    fileTreeOpen: Boolean,
    planOpen: Boolean,
    previewOpen: Boolean,
    previewReady: Boolean,
    checkpointsOpen: Boolean,
    fileActivityOpen: Boolean,
    onTerminal: () -> Unit,
    onFileTree: () -> Unit,
    onPlan: () -> Unit,
    onPreview: () -> Unit,
    onCheckpoints: () -> Unit,
    onFileActivity: () -> Unit,
    onMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CloudChip(text = workspacePath.ifBlank { "workspace" }, icon = Icons.Filled.Folder, accentColor = MaterialTheme.ledger.lampDeep)
        CloudChip(text = "$installedCount addons", icon = Icons.Filled.Extension, accentColor = MaterialTheme.ledger.deskInk)
        PanelChip(label = modeLabel, active = false, onClick = onMode)
        PanelChip(label = if (planOpen) "Hide plan" else "Plan", active = planOpen, onClick = onPlan)
        PanelChip(
            label = if (previewOpen) "Hide preview" else if (previewReady) "● Preview ready" else "Preview",
            active = previewOpen || previewReady,
            highlight = previewReady && !previewOpen,
            onClick = onPreview
        )
        PanelChip(label = if (terminalOpen) "Hide terminal" else "Terminal", active = terminalOpen, onClick = onTerminal)
        PanelChip(label = if (fileTreeOpen) "Hide files" else "Files", active = fileTreeOpen, onClick = onFileTree)
        PanelChip(label = if (checkpointsOpen) "Hide history" else "History", active = checkpointsOpen, onClick = onCheckpoints)
        PanelChip(label = if (fileActivityOpen) "Hide activity" else "Activity", active = fileActivityOpen, onClick = onFileActivity)
    }
}

@Composable
private fun PanelChip(
    label: String,
    active: Boolean,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        highlight -> Color(0xFF34C759).copy(alpha = 0.12f)
        active -> MaterialTheme.ledger.lampAmber.copy(alpha = 0.15f)
        else -> MaterialTheme.ledger.deskHairlineSoft
    }
    val fg = when {
        highlight -> Color(0xFF1B7A2B)
        active -> MaterialTheme.ledger.lampDeep
        else -> MaterialTheme.ledger.deskPaper
    }
    val border = when {
        highlight -> BorderStroke(1.dp, Color(0xFF34C759).copy(alpha = 0.3f))
        active -> BorderStroke(1.dp, MaterialTheme.ledger.lampAmber.copy(alpha = 0.3f))
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = border,
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = fg,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    isGenerating: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask the coding agent…", style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.ledger.deskInkFaint)) },
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.ledger.lampAmber,
                unfocusedBorderColor = MaterialTheme.ledger.deskHairline,
                focusedContainerColor = MaterialTheme.ledger.deskWalnut,
                unfocusedContainerColor = MaterialTheme.ledger.deskWalnut
            )
        )
        Surface(
            shape = CircleShape,
            color = if (isGenerating) Color(0xFFEF4444) else if (input.isNotBlank()) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskHairline,
            modifier = Modifier.size(48.dp).clip(CircleShape).clickable {
                if (isGenerating) onCancel() else if (input.isNotBlank()) onSend()
            }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = if (isGenerating) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isGenerating) "Stop" else "Send",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
