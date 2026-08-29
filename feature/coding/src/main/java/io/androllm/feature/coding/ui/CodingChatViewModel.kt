package io.androllm.feature.coding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.feature.coding.agent.CodingAgentCallbacks
import io.androllm.feature.coding.agent.CodingAgentException
import io.androllm.feature.coding.agent.CodingAgentLoop
import io.androllm.feature.coding.agent.CodingAvailabilityChecker
import io.androllm.feature.coding.agent.CodingCloudClient
import io.androllm.feature.coding.agent.CodingSystemPrompt
import io.androllm.feature.coding.agent.MissingAddonHandler
import io.androllm.feature.coding.environment.BackgroundServiceInfo
import io.androllm.feature.coding.environment.BackgroundServiceManager
import io.androllm.feature.coding.environment.BackgroundStartOutcome
import io.androllm.feature.coding.environment.CommandExecutor
import io.androllm.feature.coding.environment.CommandResult
import io.androllm.feature.coding.environment.EnvironmentManager
import io.androllm.feature.coding.environment.LinuxBaseManager
import io.androllm.feature.coding.environment.LocalShellBackend
import io.androllm.feature.coding.environment.MarketplaceCatalog
import io.androllm.feature.coding.environment.proot.DelegatingShellBackend
import io.androllm.feature.coding.environment.proot.ProotShellBackend
import io.androllm.feature.coding.preview.PreviewDetector
import io.androllm.feature.coding.tools.CodingToolContext
import io.androllm.feature.coding.tools.CodingToolExecutor
import io.androllm.feature.coding.tools.CodingToolRegistry
import io.androllm.feature.coding.tools.CodingToolResult
import io.androllm.feature.coding.task.AutoTestRunner
import io.androllm.feature.coding.task.CheckpointRef
import io.androllm.feature.coding.task.CheckpointStore
import io.androllm.feature.coding.task.CodingTaskState
import io.androllm.feature.coding.task.CommandRecovery
import io.androllm.feature.coding.task.FileChangeRecord
import io.androllm.feature.coding.task.RecoveryRecord
import io.androllm.feature.coding.task.TaskStateRepository
import io.androllm.feature.coding.task.TestResultRecord
import io.androllm.feature.coding.task.TestRunResult
import io.androllm.feature.coding.task.WorkspaceContext
import io.androllm.feature.coding.task.WorkspaceContextLoader
import io.androllm.feature.coding.workspace.ChatTranscriptStore
import io.androllm.feature.coding.workspace.CodingSessionState
import io.androllm.feature.coding.workspace.CodingTranscript
import io.androllm.feature.coding.workspace.CodingWorkspace
import io.androllm.feature.coding.workspace.TranscriptHistoryItem
import io.androllm.feature.coding.workspace.TranscriptMessage
import io.androllm.feature.coding.workspace.TranscriptToolCall
import io.androllm.feature.coding.workspace.WorkspaceManager
import io.androllm.feature.coding.workspace.WorkspacePathResolver
import io.androllm.feature.coding.workspace.WorkspaceSafety
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ViewModel for the dedicated AI Agent Coding Chat.
 *
 * This is a self-contained coding mode, separate from normal chat: it never
 * touches the conversation database, the local engine, or the global device-tool
 * registry. It is cloud-only (gated by [CodingAvailabilityChecker]) and operates
 * on a mandatory, sandboxed workspace folder with an attached Linux CLI.
 *
 * Responsibilities:
 *  - enforce the cloud + workspace gates,
 *  - attach/re-attach the CLI environment when the workspace changes,
 *  - drive the [CodingAgentLoop] for each user message (streaming to the UI),
 *  - surface raw terminal output, file tree, marketplace + install progress,
 *  - mediate destructive-command confirmations and missing-addon install prompts.
 */
@HiltViewModel
class CodingChatViewModel @Inject constructor(
    private val workspaceManager: WorkspaceManager,
    private val environmentManager: EnvironmentManager,
    private val linuxBaseManager: LinuxBaseManager,
    private val prootShellBackend: ProotShellBackend,
    private val backgroundServices: BackgroundServiceManager,
    private val cloudClient: CodingCloudClient,
    private val toolRegistry: CodingToolRegistry,
    private val availabilityChecker: CodingAvailabilityChecker,
    private val transcriptStore: ChatTranscriptStore,
    private val checkpointStore: CheckpointStore,
    private val taskStateRepository: TaskStateRepository,
    private val contextLoader: WorkspaceContextLoader
) : ViewModel() {

    private val _uiState = MutableStateFlow(CodingUiState())
    val uiState: StateFlow<CodingUiState> = _uiState.asStateFlow()

    private val _terminal = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminal: StateFlow<List<TerminalLine>> = _terminal.asStateFlow()

    /** Live background services (dev servers) — drives the services strip. */
    val services: StateFlow<List<BackgroundServiceInfo>> = backgroundServices.state

    private val _installedAddons = MutableStateFlow<Set<String>>(emptySet())
    val installedAddons: StateFlow<Set<String>> = _installedAddons.asStateFlow()

    private val _installProgress = MutableStateFlow<Map<String, io.androllm.feature.coding.environment.InstallProgress>>(emptyMap())
    val installProgress: StateFlow<Map<String, io.androllm.feature.coding.environment.InstallProgress>> = _installProgress.asStateFlow()

    /** Live provisioning status of the real Linux base environment (Debian + proot). */
    val linuxBaseStatus: StateFlow<io.androllm.feature.coding.environment.LinuxBaseStatus> = linuxBaseManager.status

    // Per-workspace collaborators (rebuilt when the workspace changes).
    private var commandExecutor: CommandExecutor? = null
    private var toolContext: CodingToolContext? = null
    private var agentLoop: CodingAgentLoop? = null

    // Cloud conversation history for the current session.
    private val cloudHistory = mutableListOf<CloudChatMessage>()
    private var generationJob: Job? = null

    // Confirmation + addon-install prompts suspend on these until the user answers.
    private val confirmationDeferred = MutableStateFlow<CompletableDeferred<Boolean>?>(null)
    private val addonDeferred = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    // Live sink for command output lines: while a run_command tool call is in
    // flight, the agent loop's tool context pushes each output line here and the
    // chat's tool card updates in real time (null when no tool is running).
    private val toolOutputSink = MutableStateFlow<((String) -> Unit)?>(null)

    // Diff-review gate: major file changes suspend here until the user approves
    // or rejects the shown diff (OpenCode-style review before apply).
    private val editReviewDeferred = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    // Plan approval gate: when the agent first emits a plan for a new task, the
    // draft is held here for the user to review / edit / approve / reject before
    // any code is touched. Once approved, the plan commits to state.plan and the
    // agent proceeds; on reject the agent is told the plan was rejected and asked
    // to revise.
    private val planApprovalDeferred = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    // Cached context summary for the active workspace, injected into the system
    // prompt on each fresh request so the model knows the stack before it asks.
    private var workspaceContext: WorkspaceContext? = null

    // Latest persisted task state for the active workspace (in-memory mirror
    // of the JSON file). Updated on every plan change, file change, log update,
    // server-status change, recovery, and test result.
    private var currentTaskState: CodingTaskState? = null

    // ── Preview server lifecycle ─────────────────────────────────────────────
    // The preview is ALWAYS served over a local HTTP server (never file://).
    // These track the latest detection, the in-flight server start, and whether
    // the user explicitly stopped the server (so we don't auto-restart it).
    private var lastDetection: io.androllm.feature.coding.preview.PreviewDetector.PreviewDetectionResult? = null
    private var previewServerJob: Job? = null

    @Volatile
    private var previewServerStarting = false

    @Volatile
    private var userStoppedPreview = false

    private val editReviewGate = io.androllm.feature.coding.tools.EditReviewGate { change ->
        if (!_uiState.value.reviewMajorEdits) return@EditReviewGate true
        val deferred = CompletableDeferred<Boolean>()
        editReviewDeferred.value = deferred
        _uiState.update {
            it.copy(
                pendingEditReview = PendingEditReviewUi(
                    path = change.path,
                    kind = change.kind,
                    diff = change.unifiedDiff,
                    added = change.added,
                    removed = change.removed
                )
            )
        }
        val approved = runCatching { deferred.await() }.getOrDefault(false)
        editReviewDeferred.value = null
        _uiState.update { it.copy(pendingEditReview = null) }
        approved
    }

    private val confirmationGate = io.androllm.feature.coding.environment.ConfirmationGate { command, _ ->
        awaitConfirmation(command)
    }

    private val missingAddonHandler = MissingAddonHandler { addonId, command ->
        val label = MarketplaceCatalog.find(addonId)?.name ?: addonId
        addSystemMessage("'$command' needs the $label addon. Install it to continue?")
        val approved = awaitAddonApproval(addonId)
        if (approved) environmentManager.install(addonId) else false
    }

    init {
        observeEnvironment()
        observeTranscriptChanges()
        bootstrap()
    }

    // ── Bootstrap / gating ───────────────────────────────────────────────────

    private fun bootstrap() {
        viewModelScope.launch {
            val gate = availabilityChecker.check()
            val active = workspaceManager.validateCurrent()
            _uiState.update { it.copy(gate = gate, workspace = active) }
            if (active != null) {
                attachWorkspace(active)
                restoreSession()
                restoreTranscript(active)
            }
            refreshModelLabel()
        }
    }

    private suspend fun refreshModelLabel() {
        val label = runCatching { cloudClient.activeModelLabel() }.getOrDefault("Cloud model")
        _uiState.update { it.copy(modelLabel = label) }
    }

    private fun observeEnvironment() {
        viewModelScope.launch {
            environmentManager.installed.collect { _installedAddons.value = it }
        }
        viewModelScope.launch {
            environmentManager.progress.collect { _installProgress.value = it }
        }
        // Watch background services for preview auto-detection: when a dev server
        // starts or announces a port, detect and auto-open the preview.
        viewModelScope.launch {
            backgroundServices.state.collect { svcList ->
                val runningWithPort = svcList.firstOrNull { it.running && it.urlOnDevice != null }
                if (runningWithPort != null) {
                    Timber.i("preview: dev server detected via service list: ${runningWithPort.urlOnDevice}")
                }
                // Trigger a scan whenever the service list changes; debounce via scanning logic.
                // Only scan if we have an attached workspace.
                if (_uiState.value.workspace != null && toolContext != null) {
                    scanPreview(trigger = "service_change")
                }
            }
        }
    }

    private suspend fun restoreSession() {
        val session = workspaceManager.loadSession()
        _uiState.update {
            it.copy(
                objective = session.objective,
                taskMode = io.androllm.feature.coding.agent.CodingTaskMode.fromId(session.taskMode),
                reviewMajorEdits = session.reviewMajorEdits,
                plan = session.plan.mapNotNull { line ->
                    val match = Regex("""^\[(\w+)]\s*(.*)$""").find(line) ?: return@mapNotNull null
                    io.androllm.feature.coding.tools.PlanStep(
                        text = match.groupValues[2],
                        status = io.androllm.feature.coding.tools.PlanStepStatus.fromWire(match.groupValues[1])
                    )
                }
            )
        }
    }

    // ── Workspace selection ──────────────────────────────────────────────────

    /** Called after the user picks/creates/imports a workspace in the selector. */
    fun setWorkspace(workspace: CodingWorkspace) {
        viewModelScope.launch {
            workspaceManager.setActive(workspace)
            attachWorkspace(workspace)
            _uiState.update {
                it.copy(
                    workspace = workspace,
                    gate = io.androllm.feature.coding.agent.CodingGate.Ready,
                    error = null
                )
            }
            restoreTranscript(workspace)
            persistSession { it.copy(workspaceId = workspace.id) }
        }
    }

    fun changeWorkspace() {
        // The selector screen re-runs the gate; clearing here forces re-selection.
        _uiState.update { it.copy(gate = io.androllm.feature.coding.agent.CodingGate.NeedsWorkspace("Choose a workspace folder.")) }
    }

    // ── Workspace selector support ───────────────────────────────────────────

    private val _workspaces = MutableStateFlow<List<CodingWorkspace>>(emptyList())
    val workspaces: StateFlow<List<CodingWorkspace>> = _workspaces.asStateFlow()

    private val _selectorBusy = MutableStateFlow(false)
    val selectorBusy: StateFlow<Boolean> = _selectorBusy.asStateFlow()

    private val _selectorError = MutableStateFlow<String?>(null)
    val selectorError: StateFlow<String?> = _selectorError.asStateFlow()

    fun dismissSelectorError() {
        _selectorError.value = null
    }

    fun loadWorkspaces() {
        viewModelScope.launch { _workspaces.value = workspaceManager.listWorkspaces() }
    }

    /** Opens an existing workspace then invokes [onDone] (typically: navigate). */
    fun selectWorkspace(workspace: CodingWorkspace, onDone: () -> Unit) {
        viewModelScope.launch {
            _selectorBusy.value = true
            workspaceManager.setActive(workspace)
            _selectorBusy.value = false
            onDone()
        }
    }

    /** Creates a new named workspace, marks it active, then invokes [onDone]. */
    fun createWorkspace(name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _selectorBusy.value = true
            val ws = workspaceManager.createWorkspace(name)
            workspaceManager.setActive(ws)
            _selectorBusy.value = false
            onDone()
        }
    }

    /**
     * Opens a folder chosen through the SAF picker DIRECTLY as the workspace:
     * the tree URI is resolved to its real `/storage/...` path and the agent
     * works inside that very folder (files are written there — nothing is
     * copied into app storage). Marks it active, then invokes [onDone].
     */
    fun openFolderUri(treeUri: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _selectorBusy.value = true
            _selectorError.value = null
            val primaryRoot = runCatching {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            }.getOrDefault(WorkspacePathResolver.DEFAULT_PRIMARY_ROOT)
            val path = WorkspacePathResolver.resolveTreeUri(treeUri, primaryRoot)
            if (path == null) {
                _selectorBusy.value = false
                _selectorError.value =
                    "That folder can't be opened directly. Pick a folder on this device's " +
                    "internal storage (cloud or provider folders have no real path)."
                return@launch
            }
            runCatching {
                val ws = workspaceManager.openFolder(path)
                workspaceManager.setActive(ws)
            }.onSuccess {
                _selectorBusy.value = false
                onDone()
            }.onFailure { t ->
                _selectorBusy.value = false
                _selectorError.value = t.message ?: "Could not open that folder."
            }
        }
    }

    fun deleteWorkspace(workspace: CodingWorkspace) {
        viewModelScope.launch {
            workspaceManager.deleteWorkspace(workspace)
            loadWorkspaces()
        }
    }

    private suspend fun attachWorkspace(workspace: CodingWorkspace) {
        val root = File(workspace.absolutePath)
        // Native device shell (fallback / pre-base): addon launchers are invoked
        // via `sh` function wrappers since Android blocks exec from app storage.
        val localBackend = LocalShellBackend(
            extraPathEntries = { environmentManager.pathEntries() },
            commandWrappers = { environmentManager.shellFunctionWrappers() }
        )
        // Real Linux environment: once the Debian base is provisioned, commands
        // run inside proot where real npm/python/git/... binaries execute.
        val backend = DelegatingShellBackend(
            proot = prootShellBackend,
            local = localBackend,
            preferProot = { true }
        )
        // Background services (dev servers) spawn through the same backend.
        backgroundServices.attachBackend(backend)
        val executor = CommandExecutor(
            workspaceRoot = root,
            backend = backend,
            installedAddons = { environmentManager.installedAddons() },
            confirmationGate = confirmationGate,
            backgroundServices = backgroundServices
        )
        commandExecutor = executor

        val fileOps = workspaceManager.fileOps(workspace)
        val ctx = CodingToolContext(
            workspace = workspace,
            fileOps = fileOps,
            executor = executor,
            services = backgroundServices,
            onCommandOutput = { line -> toolOutputSink.value?.invoke(line) },
            editReviewGate = editReviewGate,
            planApprovalGate = { draft -> onPlanProposed(draft) },
            onPlanUpdated = { steps -> onPlanUpdated(steps) },
            onFileTouched = { path, kind ->
                persistSession { it.withRecentFile(path) }
                // File activity feed: keep the most recent ~50 entries, newest first.
                // Reads are tracked in "recent files" (above) but NOT shown in the
                // activity feed — only real mutations (create / edit / delete) are.
                if (kind != "read") {
                    val record = FileChangeRecord(path = path, kind = kind)
                    val activity = (listOf(record) + _uiState.value.fileActivity).take(50)
                    _uiState.update { it.copy(fileActivity = activity) }
                    currentTaskState = currentTaskState?.copy(changedFiles = activity)
                    currentTaskState?.let { saveTaskState(it) }
                }
                // File touched — schedule a preview re-scan (debounced) so that
                // newly created / updated pages auto-open and refresh.
                viewModelScope.launch { scanPreview(trigger = "file_touched:$path", isRefresh = true) }
            },
            onToolUsed = { name -> persistSession { it.withTool(name) } }
        )
        toolContext = ctx

        val toolExecutor = CodingToolExecutor(toolRegistry)
        agentLoop = CodingAgentLoop(
            cloud = cloudClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            contextProvider = { toolContext ?: error("No workspace attached") },
            missingAddonHandler = missingAddonHandler
        )

        // Mirror terminal history into the UI.
        viewModelScope.launch {
            executor.history.collect { results ->
                _terminal.value = results.map { TerminalLine.from(it) }
            }
        }

        // Smart context: cache a compact project summary for the system prompt.
        viewModelScope.launch {
            val ctx = runCatching { contextLoader.load(root) }.getOrNull()
            if (ctx != null) {
                workspaceContext = ctx
                refreshSystemMessage()
            }
        }
        // Task state: if a prior task was persisted for this workspace, surface
        // a Resume / Discard prompt above the chat.
        viewModelScope.launch {
            val saved = loadTaskState(workspace.id)
            if (saved != null && saved.isResumable) {
                _uiState.update { it.copy(pendingResumeTask = saved) }
            }
        }
        // Checkpoints: load the list for the checkpoints panel.
        viewModelScope.launch {
            val list = runCatching { checkpointStore.list() }.getOrDefault(emptyList())
            _uiState.update { it.copy(checkpoints = list) }
        }

        refreshFileTree()
        // Initial preview scan for this workspace.
        viewModelScope.launch { scanPreview(trigger = "workspace_attached") }
    }

    private fun buildSystemPrompt(workspace: CodingWorkspace): String {
        val base = CodingSystemPrompt.build(
            workspace = workspace,
            environment = environmentManager,
            toolNames = toolRegistry.names(),
            objective = _uiState.value.objective,
            linuxBaseReady = linuxBaseManager.isInstalled(),
            taskMode = _uiState.value.taskMode
        )
        // Smart context: a compact project summary so the model understands the
        // stack from the first turn without having to read every file.
        val ctx = workspaceContext
        return if (ctx == null) base else base + "\n\nPROJECT CONTEXT\n" + ctx.oneLiner()
    }

    /** Replaces the system message (index 0) after mode/objective changes. */
    private fun refreshSystemMessage() {
        val workspace = _uiState.value.workspace ?: return
        if (cloudHistory.isEmpty()) return
        cloudHistory[0] = CloudChatMessage(role = "system", content = buildSystemPrompt(workspace))
    }

    private fun resetConversationForWorkspace(workspace: CodingWorkspace) {
        cloudHistory.clear()
        cloudHistory += CloudChatMessage(role = "system", content = buildSystemPrompt(workspace))
        _uiState.update {
            it.copy(
                messages = listOf(
                    CodingChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = CodingMessageRole.ASSISTANT,
                        text = "Workspace '${workspace.name}' attached at ${workspace.absolutePath}. " +
                            "I can read, edit, search and run commands here. What would you like to build or fix?"
                    )
                )
            )
        }
    }

    // ── Chat transcript persistence ──────────────────────────────────────────

    /**
     * Restores the saved chat for [workspace]: the visible message list AND
     * the cloud conversation (so the model keeps its context when the chat
     * reopens). Falls back to a fresh welcome when nothing was saved yet.
     */
    private suspend fun restoreTranscript(workspace: CodingWorkspace) {
        val transcript = runCatching { transcriptStore.load(workspace.id) }.getOrNull()
        if (transcript == null || transcript.messages.isEmpty()) {
            resetConversationForWorkspace(workspace)
            return
        }
        cloudHistory.clear()
        cloudHistory += CloudChatMessage(role = "system", content = buildSystemPrompt(workspace))
        cloudHistory += transcript.history.map { item ->
            CloudChatMessage(
                role = item.role,
                content = item.content,
                toolCallId = item.toolCallId,
                toolCalls = item.toolCalls?.mapIndexed { i, c ->
                    CloudToolCall(
                        index = i,
                        id = c.id,
                        type = "function",
                        function = CloudToolCallFunction(name = c.name, arguments = c.arguments)
                    )
                }
            )
        }
        _uiState.update {
            it.copy(
                messages = transcript.messages.map { m ->
                    CodingChatMessage(
                        id = m.id,
                        role = runCatching { CodingMessageRole.valueOf(m.role) }
                            .getOrDefault(CodingMessageRole.SYSTEM),
                        text = m.text,
                        timestampMs = m.timestampMs,
                        toolName = m.toolName,
                        isStreaming = false,
                        diff = m.diff,
                        failedCommand = m.failedCommand
                    )
                }
            )
        }
    }

    /**
     * Persists the current chat (visible transcript + cloud history) for the
     * active workspace. Triggered by the debounced message observer, and
     * best-effort from [onCleared] so backing out of the chat never loses it.
     */
    private suspend fun saveTranscript() {
        val state = _uiState.value
        val workspace = state.workspace ?: return
        if (state.messages.isEmpty()) return
        val history = cloudHistory.drop(1) // index 0 is the system prompt — rebuilt on restore
        val transcript = CodingTranscript(
            workspaceId = workspace.id,
            savedAtMs = System.currentTimeMillis(),
            messages = state.messages.map { m ->
                TranscriptMessage(
                    id = m.id,
                    role = m.role.name,
                    text = m.text,
                    timestampMs = m.timestampMs,
                    toolName = m.toolName,
                    diff = m.diff,
                    failedCommand = m.failedCommand
                )
            },
            history = history.map { h ->
                TranscriptHistoryItem(
                    role = h.role,
                    content = h.content,
                    toolCallId = h.toolCallId,
                    toolCalls = h.toolCalls?.map { c ->
                        TranscriptToolCall(
                            id = c.id.orEmpty(),
                            name = c.function?.name.orEmpty(),
                            arguments = c.function?.arguments.orEmpty()
                        )
                    }
                )
            }
        )
        runCatching { transcriptStore.save(workspace.id, transcript) }
    }

    /** Debounced auto-save: any change to the message list persists shortly after. */
    private fun observeTranscriptChanges() {
        viewModelScope.launch {
            _uiState
                .map { it.messages }
                .distinctUntilChanged()
                .debounce(TRANSCRIPT_SAVE_DEBOUNCE_MS)
                .collect { saveTranscript() }
        }
    }

    // ── Messaging / agent loop ───────────────────────────────────────────────

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val state = _uiState.value
        if (state.isGenerating) return
        val workspace = state.workspace
        val executor = agentLoop
        if (workspace == null || executor == null) {
            _uiState.update { it.copy(error = "Choose a workspace folder before sending messages.") }
            return
        }

        val userMsg = CodingChatMessage(UUID.randomUUID().toString(), CodingMessageRole.USER, trimmed)
        // A new request re-arms preview auto-start (a manual Stop only applies
        // to the previous generation).
        userStoppedPreview = false
        _uiState.update {
            it.copy(messages = it.messages + userMsg, isGenerating = true, error = null, streamingText = "")
        }
        cloudHistory += CloudChatMessage(role = "user", content = trimmed)
        persistSession { it.withCommand(trimmed) }

        generationJob = viewModelScope.launch {
            // Transcript ordering follows opencode / claude-code: assistant text
            // streams into per-round bubbles, tool cards interleave below them
            // (updating in place with live output), and the model's final answer
            // always ends up as the LAST message. Each round's text gets its own
            // bubble: when a tool call starts, the current bubble is closed; the
            // next text delta opens a fresh one below the tool result.
            var assistantMsgId: String? = null
            var anyAssistantMsg = false
            val assistantText = StringBuilder()
            var toolMsgId: String? = null
            var toolLabel = ""
            val toolOutput = StringBuilder()
            val toolOutputLock = Any()
            var lastFlushMs = 0L
            var currentToolCommand: String? = null
            val turnChangedFiles = linkedSetOf<String>()

            fun ensureAssistantMessage(): String {
                val existing = assistantMsgId
                if (existing != null) return existing
                val id = UUID.randomUUID().toString()
                assistantMsgId = id
                anyAssistantMsg = true
                assistantText.setLength(0)
                _uiState.update {
                    it.copy(
                        messages = it.messages + CodingChatMessage(
                            id, CodingMessageRole.ASSISTANT, "", isStreaming = true
                        )
                    )
                }
                return id
            }

            fun closeAssistantMessage() {
                val id = assistantMsgId ?: return
                assistantMsgId = null
                val text = assistantText.toString()
                _uiState.update { s ->
                    val messages = if (text.isBlank()) {
                        s.messages.filterNot { it.id == id }
                    } else {
                        s.messages.map { if (it.id == id) it.copy(text = text, isStreaming = false) else it }
                    }
                    s.copy(messages = messages)
                }
            }

            fun capForCard(body: String): String =
                if (body.length > TOOL_CARD_MAX_CHARS) {
                    "…[earlier output trimmed]\n" + body.takeLast(TOOL_CARD_MAX_CHARS)
                } else body

            fun flushToolCard() {
                val id = toolMsgId ?: return
                val raw = synchronized(toolOutputLock) { toolOutput.toString() }
                val shown = capForCard(raw.trimEnd())
                val text = if (shown.isBlank()) toolLabel else "$toolLabel\n$shown"
                _uiState.update { s ->
                    s.copy(messages = s.messages.map { if (it.id == id) it.copy(text = text) else it })
                }
            }

            fun openToolCard(name: String, label: String, status: String) {
                // Close the assistant bubble so the tool card renders below it.
                closeAssistantMessage()
                toolLabel = label
                toolOutput.setLength(0)
                val id = UUID.randomUUID().toString()
                toolMsgId = id
                lastFlushMs = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        messages = it.messages + CodingChatMessage(
                            id = id,
                            role = CodingMessageRole.TOOL,
                            text = label,
                            toolName = name,
                            isStreaming = true
                        ),
                        toolActivity = status
                    )
                }
            }

            val callbacks = CodingAgentCallbacks(
                onDelta = { delta ->
                    val id = ensureAssistantMessage()
                    assistantText.append(delta)
                    updateStreamingMessage(id, assistantText.toString())
                },
                onToolAnnounced = { name ->
                    // Show the card the MOMENT the model starts emitting the call —
                    // long before large arguments (e.g. a whole file's content for
                    // write_file) finish streaming, so the UI never looks stuck.
                    if (toolMsgId == null) {
                        val label = ToolCallLabels.announcing(name)
                        openToolCard(name, label, label)
                    }
                },
                onToolStart = { name, argsJson ->
                    val detailed = ToolCallLabels.describe(name, argsJson)
                    val existingId = toolMsgId
                    if (existingId != null) {
                        // Card was already announced — refine its label now that
                        // the full arguments (path, command...) are available.
                        toolLabel = detailed
                        _uiState.update { s ->
                            s.copy(messages = s.messages.map {
                                if (it.id == existingId) it.copy(text = detailed) else it
                            })
                        }
                    } else {
                        openToolCard(name, detailed, detailed)
                    }
                    _uiState.update { it.copy(toolActivity = detailed) }
                    // Remember the command so a failed run_command card gets Retry.
                    currentToolCommand = if (name == "run_command") {
                        runCatching {
                            val obj = kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
                                as? kotlinx.serialization.json.JsonObject
                            (obj?.get("command") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }.getOrNull()
                    } else null
                    // Real-time output: each command line updates the card live.
                    // (stdout and stderr pumps may invoke this from different threads.)
                    toolOutputSink.value = { line ->
                        synchronized(toolOutputLock) {
                            toolOutput.append(line).append('\n')
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastFlushMs >= TOOL_OUTPUT_FLUSH_MS) {
                            lastFlushMs = now
                            flushToolCard()
                        }
                    }
                },
                onToolResult = { name, result ->
                    toolOutputSink.value = null
                    val id = toolMsgId
                    toolMsgId = null

                    // Extract the change payload (diff) + retry command + changed path.
                    val data = (result as? CodingToolResult.Success)?.data ?: emptyMap()
                    fun str(key: String) =
                        (data[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val diffText = str("diff")?.takeIf { it.isNotBlank() }
                    val changedPath = str("path")
                    if (changedPath != null && name in FILE_MUTATING_TOOLS) {
                        turnChangedFiles += changedPath
                    }
                    val failedCmd = if (name == "run_command" && !result.isSuccess) currentToolCommand else null
                    currentToolCommand = null

                    if (id != null) {
                        val raw = synchronized(toolOutputLock) { toolOutput.toString() }
                        val body = capForCard(raw.trimEnd().ifBlank { result.summary })
                        val mark = if (result.isSuccess) "" else "\n❌ failed"
                        val finalText = "$toolLabel\n$body$mark"
                        _uiState.update { s ->
                            s.copy(messages = s.messages.map {
                                if (it.id == id) {
                                    it.copy(
                                        text = finalText,
                                        isStreaming = false,
                                        diff = diffText,
                                        failedCommand = failedCmd
                                    )
                                } else it
                            })
                        }
                    }
                    _uiState.update { it.copy(toolActivity = null) }
                    if (changedPath != null || name == "run_command") {
                        refreshFileTree()
                        // Command / file change may have produced or updated a previewable site —
                        // re-scan and auto-refresh the preview (keeps it synced with latest code).
                        viewModelScope.launch { scanPreview(trigger = "tool:$name", isRefresh = changedPath != null) }
                    } else if (name == "write_file" || name == "edit_file" || name == "replace_text") {
                        viewModelScope.launch { scanPreview(trigger = "tool:$name", isRefresh = true) }
                    }
                },
                onStatus = { s -> _uiState.update { it.copy(toolActivity = s) } },
                onMissingAddon = { addonId, _ ->
                    _uiState.update { it.copy(toolActivity = "Missing addon: $addonId") }
                }
            )

            try {
                val answer = executor.run(cloudHistory, sessionId = workspace.id, callbacks = callbacks)
                closeAssistantMessage()
                // The loop records the final assistant turn in cloudHistory itself.
                if (!anyAssistantMsg && answer.isNotBlank()) {
                    _uiState.update {
                        it.copy(
                            messages = it.messages + CodingChatMessage(
                                UUID.randomUUID().toString(), CodingMessageRole.ASSISTANT, answer
                            )
                        )
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                closeAssistantMessage()
                if (!anyAssistantMsg) {
                    _uiState.update {
                        it.copy(
                            messages = it.messages + CodingChatMessage(
                                UUID.randomUUID().toString(), CodingMessageRole.ASSISTANT, "(cancelled)"
                            )
                        )
                    }
                }
            } catch (e: CodingAgentException) {
                closeAssistantMessage()
                _uiState.update { it.copy(error = e.message) }
            } catch (t: Throwable) {
                closeAssistantMessage()
                _uiState.update { it.copy(error = t.message ?: "Unexpected error") }
            } finally {
                toolOutputSink.value = null
                // Never leave a tool card stuck in "running" state.
                val stuckToolId = toolMsgId
                if (stuckToolId != null) {
                    toolMsgId = null
                    _uiState.update { s ->
                        s.copy(messages = s.messages.map {
                            if (it.id == stuckToolId) {
                                it.copy(text = it.text + "\n(interrupted)", isStreaming = false)
                            } else it
                        })
                    }
                }
                // File change summary for this turn (OpenCode-style recap).
                val changedFiles = turnChangedFiles.toList()
                if (changedFiles.isNotEmpty()) {
                    turnChangedFiles.clear()
                    addSystemMessage("📝 Changed: " + changedFiles.joinToString(", "))
                    // Auto-test: when the project has a recognisable test command,
                    // run it once after a turn that changed files. The user sees
                    // the result in chat and the test panel.
                    val ws = _uiState.value.workspace
                    if (ws != null) {
                        val detected = runCatching {
                            AutoTestRunner { _, _ -> TestRunResult(0, "") }
                                .detect(File(ws.absolutePath))
                        }.getOrNull()
                        if (detected != null) {
                            addSystemMessage("🧪 Running ${detected.label}…")
                            runAutoTests()
                        }
                    }
                }
                // After every turn: re-scan the preview target, then (when nothing
                // is serving yet) start the right local HTTP server automatically —
                // generate → detect → serve → preview, without file:// ever.
                launch {
                    scanPreview(
                        trigger = if (changedFiles.isNotEmpty()) "turn_files_changed" else "turn_completed",
                        isRefresh = true
                    )
                    maybeAutoStartPreviewServer("turn_completed")
                }
                _uiState.update { it.copy(isGenerating = false, toolActivity = null, streamingText = "") }
            }
        }
    }

    /**
     * Stops the workflow IMMEDIATELY and completely:
     *  - cancels the agent loop coroutine,
     *  - kills any running command process (backend.cancelCurrent),
     *  - cancels an in-flight preview-server start,
     *  - resolves every pending gate (confirmation / addon / diff review) so no
     *    tool can stay suspended,
     *  - clears the generating/tool state so the UI is instantly interactive.
     * Already-running background services (dev servers) keep running — they are
     * independent of the workflow and are stopped via Stop Preview / onCleared.
     */
    fun cancelGeneration() {
        generationJob?.cancel()
        previewServerJob?.cancel()
        previewServerStarting = false
        commandExecutor?.cancel()
        confirmationDeferred.value?.complete(false)
        addonDeferred.value?.complete(false)
        editReviewDeferred.value?.complete(false)
        toolOutputSink.value = null
        _uiState.update {
            it.copy(
                isGenerating = false,
                toolActivity = null,
                pendingConfirmation = null,
                pendingAddonInstall = null,
                pendingEditReview = null,
                preview = it.preview.copy(phase = null)
            )
        }
        addSystemMessage("⏹️ Stopped.")
    }

    /**
     * Leaving the coding session stops every background server automatically
     * (IDE-like lifecycle: no orphaned dev servers after you navigate away)
     * and saves the chat transcript so reopening the chat restores it.
     */
    override fun onCleared() {
        runCatching { backgroundServices.stopAll() }
        // viewModelScope is already cancelled here — save synchronously
        // (best effort) so backing out of the chat never loses the chat.
        runCatching { runBlocking(Dispatchers.IO) { saveTranscript() } }
        super.onCleared()
    }

    /** Stops a background service (dev server) from the services strip. */
    fun stopService(id: String) {
        viewModelScope.launch {
            val summary = backgroundServices.stop(id)
            addSystemMessage(summary)
            scanPreview(trigger = "service_stopped")
        }
    }

    private fun updateStreamingMessage(id: String, text: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map {
                if (it.id == id) it.copy(text = text, isStreaming = true) else it
            })
        }
    }

    private fun addSystemMessage(text: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages + CodingChatMessage(
                id = UUID.randomUUID().toString(),
                role = CodingMessageRole.SYSTEM,
                text = text
            ))
        }
    }

    // ── Terminal / manual commands ───────────────────────────────────────────

    fun runManualCommand(command: String) {
        val executor = commandExecutor ?: return
        viewModelScope.launch {
            val result = runWithRecovery(command)
            persistSession { it.withCommand(command) }
            if (result.missingDependency != null) {
                addSystemMessage(result.missingDependency.reason)
                _uiState.update { it.copy(pendingAddonInstall = result.missingDependency.addonId) }
            }
            // Command may have produced build output or started a server — re-scan preview.
            scanPreview(trigger = "manual_command", isRefresh = true)
        }
    }

    fun cancelCommand() {
        commandExecutor?.cancel()
    }

    fun clearTerminal() {
        commandExecutor?.clearHistory()
        _terminal.value = emptyList()
    }

    // ── Confirmations ────────────────────────────────────────────────────────

    fun approveConfirmation() = confirmationDeferred.value?.complete(true)
    fun denyConfirmation() = confirmationDeferred.value?.complete(false)

    fun confirmAddonInstall(install: Boolean) {
        val deferred = addonDeferred.value
        if (deferred != null) {
            deferred.complete(install)
        } else if (install) {
            // No pending prompt (e.g. user tapped install from the marketplace list).
            _uiState.value.pendingAddonInstall?.let { installAddon(it) }
        }
    }

    private suspend fun awaitConfirmation(command: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred.value = deferred
        _uiState.update {
            it.copy(
                pendingConfirmation = PendingCodingConfirmation(
                    id = UUID.randomUUID().toString(),
                    title = "Approve destructive command",
                    detail = command
                )
            )
        }
        val result = runCatching { deferred.await() }.getOrDefault(false)
        confirmationDeferred.value = null
        _uiState.update { it.copy(pendingConfirmation = null) }
        return result
    }

    private suspend fun awaitAddonApproval(addonId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        addonDeferred.value = deferred
        _uiState.update { it.copy(pendingAddonInstall = addonId) }
        val result = runCatching { deferred.await() }.getOrDefault(false)
        addonDeferred.value = null
        _uiState.update { it.copy(pendingAddonInstall = null) }
        return result
    }

    // ── Marketplace / environment ────────────────────────────────────────────

    fun installAddon(addonId: String) {
        viewModelScope.launch {
            environmentManager.install(addonId)
            persistSession { it.copy(installedAddons = environmentManager.installedAddons().toList()) }
        }
    }

    fun retryInstall(addonId: String) {
        viewModelScope.launch { environmentManager.retryInstall(addonId) }
    }

    fun uninstallAddon(addonId: String) {
        viewModelScope.launch {
            environmentManager.uninstall(addonId)
            persistSession { it.copy(installedAddons = environmentManager.installedAddons().toList()) }
        }
    }

    fun provisionBase() {
        viewModelScope.launch {
            addSystemMessage("Provisioning the Linux base environment (Debian + proot)...")
            val ok = linuxBaseManager.provision()
            addSystemMessage(
                if (ok) "Linux base ready. Marketplace addons now install real runtimes via apt."
                else "Linux base provisioning failed — see the Environment panel to retry."
            )
        }
    }

    fun toggleMarketplace(show: Boolean) = _uiState.update { it.copy(showMarketplace = show) }
    fun toggleEnvironment(show: Boolean) = _uiState.update { it.copy(showEnvironment = show) }

    fun setObjective(objective: String) {
        _uiState.update { it.copy(objective = objective) }
        refreshSystemMessage()
        persistSession { it.copy(objective = objective) }
    }

    // ── Task mode / quick actions ────────────────────────────────────────────

    /** Switches the task mode and rewrites the system prompt accordingly. */
    fun setTaskMode(mode: io.androllm.feature.coding.agent.CodingTaskMode) {
        if (_uiState.value.taskMode == mode) return
        _uiState.update { it.copy(taskMode = mode) }
        refreshSystemMessage()
        persistSession { it.copy(taskMode = mode.name) }
        if (mode != io.androllm.feature.coding.agent.CodingTaskMode.GENERAL) {
            addSystemMessage("Task mode: ${mode.emoji} ${mode.label}")
        }
    }

    /** Sends a templated quick-action prompt (Build / Test / Lint / Run / Inspect). */
    fun sendQuickAction(action: QuickAction) {
        sendMessage(action.prompt)
    }

    // ── Edit review (diff approve/reject) ────────────────────────────────────

    fun approveEditReview() {
        editReviewDeferred.value?.complete(true)
    }

    fun rejectEditReview() {
        editReviewDeferred.value?.complete(false)
    }

    /** Toggles whether major file changes require diff approval. */
    fun setReviewMajorEdits(enabled: Boolean) {
        _uiState.update { it.copy(reviewMajorEdits = enabled) }
        persistSession { it.copy(reviewMajorEdits = enabled) }
    }

    // ── Plan ─────────────────────────────────────────────────────────────────

    /**
     * True after the user has approved the first plan for the current task.
     * Subsequent `update_plan` calls bypass the approval gate until a new task
     * is started (see [newTask]).
     */
    private var planApprovedForTask: Boolean = false

    /**
     * Gate invoked by [UpdatePlanTool] the first time the agent emits a plan
     * for a fresh task. Surfaces the draft as `pendingPlanApproval`, suspends
     * on a deferred, and returns the user's verdict. After approval, the
     * committed plan is reflected in `state.plan` by the time control returns.
     */
    private suspend fun onPlanProposed(draft: List<io.androllm.feature.coding.tools.PlanStep>): Boolean {
        if (planApprovedForTask) return true
        val deferred = CompletableDeferred<Boolean>()
        planApprovalDeferred.value = deferred
        _uiState.update { it.copy(pendingPlanApproval = draft) }
        addSystemMessage("📋 Plan proposed (" + draft.size + " steps) — review and approve to continue.")
        val verdict = runCatching { deferred.await() }.getOrDefault(false)
        planApprovalDeferred.value = null
        _uiState.update { it.copy(pendingPlanApproval = null) }
        if (verdict) planApprovedForTask = true
        return verdict
    }

    private fun onPlanUpdated(steps: List<io.androllm.feature.coding.tools.PlanStep>) {
        _uiState.update { it.copy(plan = steps) }
        persistSession {
            it.copy(plan = steps.map { s -> "[${s.status.wire}] ${s.text}" })
        }
        currentTaskState = currentTaskState?.copy(
            plan = steps,
            currentStepIndex = steps.indexOfFirst { it.status == io.androllm.feature.coding.tools.PlanStepStatus.IN_PROGRESS }
                .coerceAtLeast(if (steps.all { it.status == io.androllm.feature.coding.tools.PlanStepStatus.DONE }) steps.lastIndex + 1 else 0)
        )
        currentTaskState?.let { saveTaskState(it) }
    }

    /** Approve the pending plan (the user can edit it first via [editPlanStep] etc.). */
    fun approvePlan(editedDraft: List<io.androllm.feature.coding.tools.PlanStep>? = null) {
        val draft = editedDraft ?: _uiState.value.pendingPlanApproval ?: return
        val normalized = draft.map { step ->
            if (step.status == io.androllm.feature.coding.tools.PlanStepStatus.IN_PROGRESS) {
                step.copy(status = io.androllm.feature.coding.tools.PlanStepStatus.PENDING)
            } else step
        }
        // Mark exactly one step in_progress (the first) so the model can start.
        val launched = normalized.mapIndexed { i, s ->
            if (i == 0) s.copy(status = io.androllm.feature.coding.tools.PlanStepStatus.IN_PROGRESS) else s
        }
        onPlanUpdated(launched)
        planApprovalDeferred.value?.complete(true)
    }

    fun rejectPlan() {
        planApprovalDeferred.value?.complete(false)
    }

    /** User-edited a pending plan step text. Only the pending draft is affected. */
    fun editPlanStep(stepId: String, newText: String) {
        val draft = _uiState.value.pendingPlanApproval ?: return
        val next = draft.map { if (it.id == stepId) it.copy(text = newText) else it }
        _uiState.update { it.copy(pendingPlanApproval = next) }
    }

    fun addPlanStep(text: String) {
        val trimmed = text.trim().ifBlank { return }
        val draft = _uiState.value.pendingPlanApproval ?: return
        _uiState.update {
            it.copy(pendingPlanApproval = draft + io.androllm.feature.coding.tools.PlanStep.pending(trimmed))
        }
    }

    fun removePlanStep(stepId: String) {
        val draft = _uiState.value.pendingPlanApproval ?: return
        _uiState.update { it.copy(pendingPlanApproval = draft.filterNot { it.id == stepId }) }
    }

    fun movePlanStep(stepId: String, delta: Int) {
        val draft = _uiState.value.pendingPlanApproval ?: return
        val idx = draft.indexOfFirst { it.id == stepId }
        if (idx < 0) return
        val newIdx = (idx + delta).coerceIn(0, draft.lastIndex)
        if (newIdx == idx) return
        val mutable = draft.toMutableList()
        val moved = mutable.removeAt(idx)
        mutable.add(newIdx, moved)
        _uiState.update { it.copy(pendingPlanApproval = mutable.toList()) }
    }

    // ── Checkpoints ──────────────────────────────────────────────────────────

    fun toggleCheckpoints() = _uiState.update { it.copy(showCheckpoints = !it.showCheckpoints) }
    fun toggleFileActivity() = _uiState.update { it.copy(showFileActivity = !it.showFileActivity) }

    /**
     * Creates a named checkpoint of the current workspace contents. Snapshots
     * the workspace on the IO dispatcher and persists a [CheckpointRef] in the
     * current task state. The user sees the new checkpoint at the top of the
     * checkpoints panel.
     */
    fun createCheckpoint(name: String, onDone: (Boolean) -> Unit = {}) {
        val root = toolContext?.fileOps?.root() ?: run { onDone(false); return }
        viewModelScope.launch {
            val resolvedName = name.ifBlank { "Checkpoint ${System.currentTimeMillis() / 1000}" }
            val snapshot = runCatching { checkpointStore.snapshot(root) }.getOrElse { emptyList() }
            if (snapshot.isEmpty()) { onDone(false); return@launch }
            val ref = runCatching { checkpointStore.create(resolvedName, snapshot) }.getOrNull()
            if (ref == null) { onDone(false); return@launch }
            val newList = listOf(ref) + _uiState.value.checkpoints
            _uiState.update { it.copy(checkpoints = newList) }
            currentTaskState = currentTaskState?.copy(checkpoints = newList)
            currentTaskState?.let { saveTaskState(it) }
            addSystemMessage("📸 Checkpoint '${ref.name}' saved (${ref.fileCount} files).")
            onDone(true)
        }
    }

    /** Restores a checkpoint: overwrites the workspace with the snapshot's contents. */
    fun restoreCheckpoint(checkpointId: String) {
        val root = toolContext?.fileOps?.root() ?: return
        viewModelScope.launch {
            val written = runCatching { checkpointStore.restore(checkpointId, root) }.getOrDefault(0)
            if (written == 0) {
                addSystemMessage("⚠️ Checkpoint restore failed or empty.")
                return@launch
            }
            addSystemMessage("↩️ Restored checkpoint — $written files written. Workspace re-scanning…")
            refreshFileTree()
            scanPreview(trigger = "checkpoint_restored", isRefresh = true)
        }
    }

    fun deleteCheckpoint(checkpointId: String) {
        viewModelScope.launch {
            runCatching { checkpointStore.delete(checkpointId) }
            val newList = _uiState.value.checkpoints.filterNot { it.id == checkpointId }
            _uiState.update { it.copy(checkpoints = newList) }
            currentTaskState = currentTaskState?.copy(checkpoints = newList)
            currentTaskState?.let { saveTaskState(it) }
        }
    }

    fun refreshCheckpoints() {
        viewModelScope.launch {
            val list = runCatching { checkpointStore.list() }.getOrDefault(emptyList())
            _uiState.update { it.copy(checkpoints = list) }
            currentTaskState = currentTaskState?.copy(checkpoints = list)
            currentTaskState?.let { saveTaskState(it) }
        }
    }

    // ── Auto-recovery (manual commands) ──────────────────────────────────────

    /**
     * Wraps the manual command path with one safe auto-recovery attempt. When
     * a command fails and `CommandRecovery` recognises the failure (peer-dep
     * conflict, port collision, missing Python module, etc.), the recovery
     * command is run and the original is re-issued once. The user always sees
     * what happened via system messages.
     */
    private suspend fun runWithRecovery(command: String, workingDir: String = ""): io.androllm.feature.coding.environment.CommandResult {
        val executor = commandExecutor ?: error("No workspace attached")
        val first = executor.execute(command, workingDir)
        if (first.isSuccess) return first
        if (first.cancelled) return first
        if (first.missingDependency != null) return first
        val plan = CommandRecovery.suggest(command, first.combinedOutput.takeLast(4_000))
            ?: return first
        addSystemMessage("🛠 Auto-recovery (${plan.category}): ${plan.rationale}")
        val recovery = executor.execute(plan.command, plan.workingDir ?: workingDir)
        val succeeded = recovery.isSuccess
        val record = RecoveryRecord(
            originalCommand = command,
            recoveryCommand = plan.command,
            category = plan.category,
            succeeded = succeeded
        )
        _uiState.update { it.copy(lastRecovery = record) }
        addSystemMessage(
            if (succeeded) "✅ Auto-recovery succeeded — re-running the original command."
            else "❌ Auto-recovery did not fix the failure — surfacing to the agent."
        )
        if (!succeeded) return first
        val retry = executor.execute(command, workingDir)
        return retry
    }

    // ── Auto-test runner ─────────────────────────────────────────────────────

    /**
     * Runs the project's test command (if a known marker is present), records
     * the result, and surfaces a system message. Called automatically after
     * each turn that contains file changes, and also exposed as a button in
     * the test panel. Returns synchronously; the actual run happens on the
     * viewModel scope.
     */
    fun runAutoTests() {
        val workspace = _uiState.value.workspace ?: return
        val root = File(workspace.absolutePath)
        val runner = AutoTestRunner { command, workingDir ->
            val exec = commandExecutor ?: return@AutoTestRunner TestRunResult(exitCode = -1, combinedOutput = "no executor")
            val r = runWithRecovery(command, workingDir.path)
            TestRunResult(exitCode = r.exitCode, combinedOutput = r.combinedOutput)
        }
        viewModelScope.launch {
            val result = runCatching { runner.run(root) }.getOrNull() ?: return@launch
            _uiState.update { it.copy(lastTestResult = result) }
            currentTaskState = currentTaskState?.copy(lastTestResult = result)
            currentTaskState?.let { saveTaskState(it) }
            addSystemMessage(
                if (result.isPass) "✅ Tests passed (${result.framework}, ${result.passed} passed)."
                else "❌ Tests failed (${result.framework}, ${result.passed} passed, ${result.failed} failed)."
            )
        }
    }

    // ── Mid-course interrupt ─────────────────────────────────────────────────

    /**
     * Pauses the running generation, appends the user's message as a new
     * directive step at the END of the current plan (so the agent picks it up
     * without losing context), and lets the loop resume from the current
     * step. The user's message is also pushed to the chat and to the cloud
     * history so the model sees it.
     */
    fun sendInterrupt(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        // The user is taking control — flip the plan gate so a future
        // update_plan is treated as a revision (not a new approval cycle).
        planApprovedForTask = true
        addSystemMessage("⏸ Interrupt: " + trimmed)
        // Append as a directive in the plan, after the current step.
        val plan = _uiState.value.plan
        val cur = plan.indexOfFirst { it.status == io.androllm.feature.coding.tools.PlanStepStatus.IN_PROGRESS }
        val directive = io.androllm.feature.coding.tools.PlanStep.pending("User directive: $trimmed")
        val next = plan.toMutableList()
        val insertAt = if (cur < 0) plan.size else cur + 1
        next.add(insertAt, directive)
        _uiState.update { it.copy(plan = next) }
        cloudHistory += CloudChatMessage(role = "user", content = "DIRECTIVE: $trimmed")
    }

    // ── Task state persistence ──────────────────────────────────────────────

    private fun saveTaskState(state: CodingTaskState) {
        currentTaskState = state
        viewModelScope.launch { runCatching { taskStateRepository.save(state) } }
    }

    private suspend fun loadTaskState(workspaceId: String): CodingTaskState? =
        runCatching { taskStateRepository.load(workspaceId) }.getOrNull()

    /** Resume a saved task: restore its plan + file activity to the UI. */
    fun resumeTask(task: CodingTaskState) {
        _uiState.update {
            it.copy(
                plan = task.plan,
                fileActivity = task.changedFiles,
                pendingResumeTask = null
            )
        }
        currentTaskState = task
        planApprovedForTask = task.plan.isNotEmpty()
        addSystemMessage("▶ Resumed task from ${formatTimestamp(task.lastUpdatedMs)}.")
    }

    /** Discard a saved task and clear its persisted file. */
    fun discardPendingTask(task: CodingTaskState) {
        viewModelScope.launch { runCatching { taskStateRepository.clear(task.workspaceId) } }
        _uiState.update { it.copy(pendingResumeTask = null) }
    }

    private fun formatTimestamp(ms: Long): String {
        if (ms <= 0) return "earlier"
        val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
        return sdf.format(java.util.Date(ms))
    }

    // ── Preview auto-detection + lifecycle ─────────────────────────────────

    /**
     * Scans the workspace + running services to infer the best preview target.
     *
     * Triggered after: workspace attach, file writes/edits, command runs,
     * background service starts, manual refresh, and construction.
     *
     * Emits detailed Timber logs for every decision so the preview bug is easy
     * to debug later (workspace scanned, framework detected, preview target
     * found, preview opened/failed/refreshed/skipped, fallback used).
     */
    suspend fun scanPreview(trigger: String = "manual", isRefresh: Boolean = false) {
        val ctx = toolContext ?: return
        val workspace = _uiState.value.workspace ?: return
        val previous = _uiState.value.preview

        _uiState.update { it.copy(preview = it.preview.copy(status = PreviewUiStatus.SCANNING)) }
        Timber.i("preview scan: trigger=$trigger workspace=${workspace.absolutePath} isRefresh=$isRefresh")

        val result = withContext(Dispatchers.IO) {
            runCatching {
                PreviewDetector.detect(
                    exists = { p -> runCatching { ctx.fileOps.exists(p) }.getOrDefault(false) },
                    readHead = { p -> runCatching { ctx.fileOps.readFile(p, 12_000) }.getOrNull() },
                    services = backgroundServices.state.value,
                    workspacePath = workspace.absolutePath
                )
            }.getOrElse {
                Timber.e(it, "preview scan failed")
                PreviewDetector.PreviewDetectionResult(
                    status = PreviewDetector.PreviewStatus.FAILED,
                    framework = null,
                    target = null,
                    candidates = emptyList(),
                    logs = listOf("preview failed: ${it.message}"),
                    suggestion = "Preview scan failed: ${it.message}",
                    autoOpen = false
                )
            }
        }

        // Map detector result → UI state. The preview is ALWAYS served over HTTP:
        // a running dev server supplies the URL; static/build targets and dev
        // commands are "startable" — the app launches the right local HTTP
        // server (never a raw file:// URL).
        lastDetection = result

        // While a server start is in flight, the start flow owns the UI state.
        if (previewServerStarting) {
            Timber.i("preview scan during server start — state owned by start flow (trigger=$trigger)")
            return
        }

        val serverRunning = backgroundServices.state.value.any { it.running && it.port != null }
        val resolvedUrl: String? = when {
            result.target?.kind == PreviewDetector.PreviewKind.DEV_SERVER -> result.target.url
            else -> null
        }
        val startable = !serverRunning && result.target?.kind in setOf(
            PreviewDetector.PreviewKind.STATIC_FILE,
            PreviewDetector.PreviewKind.BUILD_OUTPUT,
            PreviewDetector.PreviewKind.DEV_COMMAND
        )

        // The preview is ONLY available while a local server is actually
        // running: a detected target without a live server is "startable",
        // never "ready".
        val newStatus = when {
            resolvedUrl != null && serverRunning -> PreviewUiStatus.READY
            result.status == PreviewDetector.PreviewStatus.FAILED -> PreviewUiStatus.FAILED
            else -> PreviewUiStatus.NOT_AVAILABLE
        }

        val isNewlyReady = newStatus == PreviewUiStatus.READY && previous.status != PreviewUiStatus.READY
        val shouldAutoOpen = isNewlyReady && resolvedUrl != null
        val tick = if (isRefresh && newStatus == PreviewUiStatus.READY) previous.refreshTick + 1 else previous.refreshTick

        if (isNewlyReady) Timber.i("preview: target became READY: ${result.target?.title} url=$resolvedUrl")
        if (isRefresh && newStatus == PreviewUiStatus.READY) Timber.i("preview refreshed: $resolvedUrl (trigger=$trigger)")
        if (newStatus == PreviewUiStatus.NOT_AVAILABLE) Timber.i("preview not available: ${result.suggestion}")
        if (result.status == PreviewDetector.PreviewStatus.FAILED) Timber.w("preview failed: ${result.suggestion}")

        _uiState.update { s ->
            s.copy(
                preview = s.preview.copy(
                    status = newStatus,
                    framework = result.framework,
                    targetPath = result.target?.relativePath,
                    targetUrl = resolvedUrl,
                    targetTitle = if (resolvedUrl != null) result.target?.title else s.preview.targetTitle,
                    suggestion = if (startable) startSuggestion(result) else result.suggestion,
                    logs = result.logs,
                    autoOpened = shouldAutoOpen,
                    refreshTick = tick,
                    lastScannedAtMs = System.currentTimeMillis(),
                    error = if (newStatus == PreviewUiStatus.FAILED) result.suggestion else s.preview.error,
                    canStartServer = startable,
                    // A dead server invalidates the tracked preview service.
                    serverServiceId = if (newStatus == PreviewUiStatus.READY) s.preview.serverServiceId else null
                ),
                // Keep legacy previewUrl in sync so existing UI that reads it stays correct.
                previewUrl = if (shouldAutoOpen && resolvedUrl != null) resolvedUrl else s.previewUrl
            )
        }

        if (shouldAutoOpen && resolvedUrl != null) {
            Timber.i("preview ready: $resolvedUrl (trigger=$trigger)")
            addSystemMessage("👁️ Preview ready: $resolvedUrl — tap Preview to open it in your browser.")
        }
    }

    /** Actionable copy for "a local server can be started for this target". */
    private fun startSuggestion(result: PreviewDetector.PreviewDetectionResult): String =
        when (result.target?.kind) {
            PreviewDetector.PreviewKind.DEV_COMMAND ->
                "Start Preview launches the dev server (" +
                    (result.stackReport?.devCommands?.firstOrNull() ?: "npm run dev") +
                    "), waits until it responds, then you can open the page in your browser."
            PreviewDetector.PreviewKind.STATIC_FILE,
            PreviewDetector.PreviewKind.BUILD_OUTPUT ->
                "Start Preview serves ${result.target.relativePath} through a local HTTP server — then you can open it in your browser."
            else -> result.suggestion ?: ""
        }

    // ── Preview server start / stop (IDE-like lifecycle) ────────────────────

    /**
     * Starts the right local HTTP server for the detected preview target:
     * static sites → python3/node static server; framework projects → their real
     * dev server. Waits for the port, then polls until the server actually
     * responds, and only THEN marks the preview ready (http://localhost:PORT).
     */
    fun startPreviewServer(trigger: String = "manual") {
        if (previewServerStarting) return
        // Already serving → just refresh the WebView, don't restart anything.
        if (backgroundServices.state.value.any { it.running && it.port != null }) {
            viewModelScope.launch { scanPreview(trigger = "start_already_serving", isRefresh = true) }
            return
        }
        val detection = lastDetection
        val target = detection?.target
        if (target == null || target.kind == PreviewDetector.PreviewKind.DEV_SERVER) {
            viewModelScope.launch { scanPreview(trigger = "start_no_target") }
            return
        }

        val plan = io.androllm.feature.coding.preview.PreviewServerPlanner.plan(
            target = target,
            devCommands = detection.stackReport?.devCommands ?: emptyList(),
            installedAddons = environmentManager.installedAddons()
        )
        if (plan == null) {
            _uiState.update {
                it.copy(preview = it.preview.copy(suggestion = "No server command is available for this project yet."))
            }
            return
        }
        if (plan.requiredAddonId != null) {
            val label = MarketplaceCatalog.find(plan.requiredAddonId)?.name ?: plan.requiredAddonId
            addSystemMessage("The preview server needs the $label addon. Install it, then tap Start Preview again.")
            _uiState.update {
                it.copy(
                    pendingAddonInstall = plan.requiredAddonId,
                    preview = it.preview.copy(suggestion = "Install the $label addon to start the preview server.")
                )
            }
            return
        }
        previewServerJob = viewModelScope.launch { runPreviewServer(plan, trigger) }
    }

    private suspend fun runPreviewServer(
        plan: io.androllm.feature.coding.preview.PreviewServerPlan,
        trigger: String
    ) {
        val executor = commandExecutor ?: return
        previewServerStarting = true
        setPreviewPhase("Starting local server...")
        Timber.i("preview server starting: ${plan.command} (trigger=$trigger)")
        addSystemMessage("🚀 ${plan.description} — starting...")

        val outcome = executor.executeBackground(plan.command, plan.workingDir)
        when (outcome) {
            is BackgroundStartOutcome.Failed -> {
                previewServerStarting = false
                setPreviewPhase(null)
                Timber.w("preview server failed to start: ${outcome.summary}")
                _uiState.update {
                    it.copy(
                        preview = it.preview.copy(
                            status = PreviewUiStatus.FAILED,
                            error = outcome.summary,
                            serverLog = outcome.summary
                        )
                    )
                }
                if (outcome.missingAddonId != null) {
                    _uiState.update { it.copy(pendingAddonInstall = outcome.missingAddonId) }
                }
            }
            is BackgroundStartOutcome.Started -> {
                val serviceId = outcome.service.id
                _uiState.update { it.copy(preview = it.preview.copy(serverServiceId = serviceId)) }

                // Wait until the server announces a port (or crashes).
                setPreviewPhase("Waiting for the server to announce a port...")
                var port = outcome.service.port
                val portDeadline = System.currentTimeMillis() + PORT_WAIT_MS
                while (port == null && System.currentTimeMillis() < portDeadline) {
                    delay(400)
                    val svc = backgroundServices.state.value.firstOrNull { it.id == serviceId }
                    if (svc != null && !svc.running) break
                    port = svc?.port
                }
                if (port == null) {
                    val log = backgroundServices.logTail(serviceId, 2000).orEmpty()
                    previewServerStarting = false
                    setPreviewPhase(null)
                    Timber.w("preview server did not announce a port; log:\n$log")
                    _uiState.update {
                        it.copy(
                            preview = it.preview.copy(
                                status = PreviewUiStatus.FAILED,
                                error = "The server started but did not announce a port.",
                                serverLog = log.ifBlank { outcome.summary }
                            )
                        )
                    }
                    return
                }

                // Wait until the server is ACTUALLY reachable over HTTP.
                setPreviewPhase("Waiting for http://localhost:$port...")
                var reachable = false
                val reachDeadline = System.currentTimeMillis() + REACH_WAIT_MS
                while (!reachable && System.currentTimeMillis() < reachDeadline) {
                    reachable = withContext(Dispatchers.IO) {
                        io.androllm.feature.coding.preview.HttpReachability.check("http://127.0.0.1:$port/")
                    }
                    if (!reachable) delay(500)
                }
                previewServerStarting = false

                if (reachable) {
                    setPreviewPhase("Preview ready.")
                    Timber.i("preview server ready: http://localhost:$port")
                    val url = "http://localhost:$port"
                    _uiState.update {
                        it.copy(
                            preview = it.preview.copy(
                                status = PreviewUiStatus.READY,
                                targetUrl = url,
                                targetTitle = plan.description,
                                autoOpened = true,
                                error = null,
                                serverLog = null,
                                refreshTick = it.preview.refreshTick + 1
                            ),
                            previewUrl = url
                        )
                    }
                    addSystemMessage("👁️ Preview ready at $url — tap Preview to open it in your browser.")
                } else {
                    val log = backgroundServices.logTail(serviceId, 2000).orEmpty()
                    setPreviewPhase(null)
                    Timber.w("preview server not reachable at port $port; log:\n$log")
                    _uiState.update {
                        it.copy(
                            preview = it.preview.copy(
                                status = PreviewUiStatus.FAILED,
                                error = "The server is running but http://localhost:$port did not respond in time.",
                                serverLog = log
                            )
                        )
                    }
                }
            }
        }
    }

    /** Stops the preview server (IDE-like "Stop Preview"). */
    fun stopPreviewServer() {
        val id = _uiState.value.preview.serverServiceId
        userStoppedPreview = true
        viewModelScope.launch {
            setPreviewPhase("Stopping server...")
            if (id != null) {
                backgroundServices.stop(id)
            } else {
                // No tracked id — stop any running preview-like services.
                backgroundServices.state.value
                    .filter { it.running }
                    .forEach { backgroundServices.stop(it.id) }
            }
            _uiState.update {
                it.copy(
                    preview = it.preview.copy(
                        status = PreviewUiStatus.NOT_AVAILABLE,
                        targetUrl = null,
                        serverServiceId = null,
                        autoOpened = false,
                        error = null,
                        serverLog = null,
                        canStartServer = true,
                        suggestion = "Server stopped. Tap Start Preview to launch it again."
                    ),
                    previewUrl = ""
                )
            }
            setPreviewPhase(null)
            addSystemMessage("⏹️ Preview server stopped.")
            Timber.i("preview server stopped by user")
        }
    }

    /**
     * Auto-starts the preview server after a turn completes — only when a
     * startable target exists, nothing is serving yet, and the user has not
     * manually stopped the preview for this generation.
     */
    private fun maybeAutoStartPreviewServer(trigger: String) {
        if (userStoppedPreview || previewServerStarting) return
        if (backgroundServices.state.value.any { it.running && it.port != null }) return
        val target = lastDetection?.target ?: return
        if (target.kind !in setOf(
                PreviewDetector.PreviewKind.STATIC_FILE,
                PreviewDetector.PreviewKind.BUILD_OUTPUT,
                PreviewDetector.PreviewKind.DEV_COMMAND
            )
        ) return
        Timber.i("preview server auto-start after turn (trigger=$trigger)")
        startPreviewServer("auto:$trigger")
    }

    private fun setPreviewPhase(phase: String?) {
        _uiState.update { it.copy(preview = it.preview.copy(phase = phase)) }
    }

    fun refreshPreview() {
        viewModelScope.launch {
            Timber.i("preview refreshed: manual refresh requested")
            scanPreview(trigger = "manual_refresh", isRefresh = true)
        }
    }

    // ── Retry failed commands ────────────────────────────────────────────────

    /**
     * Re-runs a failed command from its tool card (user-initiated retry). The
     * output streams into a fresh tool card exactly like an agent-run command.
     */
    fun retryCommand(command: String) {
        val executor = commandExecutor ?: return
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val cardId = UUID.randomUUID().toString()
            val label = "$ $trimmed   ↻ retry"
            val output = StringBuilder()
            val lock = Any()
            var lastFlush = 0L
            fun flush(final: Boolean, suffix: String = "") {
                val raw = synchronized(lock) { output.toString() }.trimEnd()
                val shown = if (raw.length > 4000) "…[earlier output trimmed]\n" + raw.takeLast(4000) else raw
                val text = (if (shown.isBlank()) label else "$label\n$shown") + suffix
                _uiState.update { s ->
                    s.copy(messages = s.messages.map {
                        if (it.id == cardId) it.copy(text = text, isStreaming = !final) else it
                    })
                }
            }
            _uiState.update {
                it.copy(messages = it.messages + CodingChatMessage(
                    id = cardId,
                    role = CodingMessageRole.TOOL,
                    text = label,
                    toolName = "run_command",
                    isStreaming = true
                ))
            }
            val result = executor.execute(trimmed, onOutput = { line ->
                synchronized(lock) { output.append(line).append('\n') }
                val now = System.currentTimeMillis()
                if (now - lastFlush >= 100L) {
                    lastFlush = now
                    flush(final = false)
                }
            })
            val suffix = if (result.isSuccess) "\n[exit ${result.exitCode}]" else "\n❌ [exit ${result.exitCode}]"
            flush(final = true, suffix = suffix)
            scanPreview(trigger = "retry_command", isRefresh = true)
        }
    }

    fun refreshFileTree() {
        val ctx = toolContext ?: return
        viewModelScope.launch {
            val tree = withContext(Dispatchers.IO) {
                runCatching { ctx.fileOps.fileTree(maxDepth = 3) }.getOrNull()
            }
            _uiState.update { it.copy(fileTree = tree) }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ── Session persistence ──────────────────────────────────────────────────

    private fun persistSession(transform: (CodingSessionState) -> CodingSessionState) {
        viewModelScope.launch {
            val current = workspaceManager.loadSession()
            workspaceManager.saveSession(transform(current))
        }
    }

    companion object {
        /** Max characters kept in a tool card (tail is preserved, head trimmed). */
        private const val TOOL_CARD_MAX_CHARS = 4000

        /** Min interval between live tool-card refreshes (real-time but not spammy). */
        private const val TOOL_OUTPUT_FLUSH_MS = 100L

        /** Quiet period after the last message change before the transcript is saved. */
        private const val TRANSCRIPT_SAVE_DEBOUNCE_MS = 700L

        /** How long to wait for a started server to announce its port. */
        private const val PORT_WAIT_MS = 30_000L

        /** How long to poll a server until it actually answers over HTTP. */
        private const val REACH_WAIT_MS = 20_000L

        /** Tools whose results carry a changed-path payload (change summary). */
        private val FILE_MUTATING_TOOLS = setOf("write_file", "edit_file", "replace_text")
    }
}
