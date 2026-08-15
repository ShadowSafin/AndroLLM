package io.androllm.feature.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.tools.planner.LocalToolCapability
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.prompt.ToolPromptBuilder
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTrace
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One registered tool, rendered on the Tool Debug diagnostics card. */
data class RegisteredToolInfo(
    val name: String,
    val description: String,
    val category: String,
    val enabled: Boolean,
    val requiresConfirmation: Boolean,
    /** JSON Schema for the tool's arguments (the function schema). */
    val parametersJson: String
)

/**
 * Snapshot of the tool-calling system shown at the top of the Tool Debug
 * screen: registry status, prompt-injection status, and function schemas.
 */
data class ToolDiagnostics(
    /** Master switch state (Settings → Automation). */
    val pipelineEnabled: Boolean = false,
    /** Tools present in the [ToolRegistry]. */
    val registeredCount: Int = 0,
    val tools: List<RegisteredToolInfo> = emptyList(),
    /** How many of those tools are advertised into the chat system prompt. */
    val advertisedCount: Int = 0,
    /** Preview of the injected AVAILABLE TOOLS block. */
    val advertisementPreview: String = "",
    /** Live assessment of the loaded model's tool-call capability. */
    val capability: LocalToolCapability = LocalToolCapability()
)

/**
 * Tool Debug ViewModel — mirrors the shared, bounded [ToolExecutionTraceStore]
 * so every chat/voice turn's tool calls (prompt → tool → args → status →
 * result → error → timing → LLM output) are visible in one place, and exposes
 * live registry + prompt-injection diagnostics for the same screen.
 */
@HiltViewModel
class ToolDebugViewModel @Inject constructor(
    private val traceStore: ToolExecutionTraceStore,
    private val toolRegistry: ToolRegistry,
    private val toolPromptBuilder: ToolPromptBuilder,
    private val settingsStore: AutomationSettingsStore,
    private val toolPlanner: ToolPlanner
) : ViewModel() {

    val traces: StateFlow<List<ToolExecutionTrace>> = traceStore.traces

    /** Live model tool-call capability (updates with every planning round). */
    val capability: StateFlow<LocalToolCapability> = toolPlanner.capability

    private val _diagnostics = MutableStateFlow(ToolDiagnostics())
    val diagnostics: StateFlow<ToolDiagnostics> = _diagnostics.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { refreshDiagnostics(it) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshDiagnostics(settingsStore.current())
        }
    }

    private suspend fun refreshDiagnostics(settings: AutomationSettings) {
        val tools = toolRegistry.all().sortedBy { it.spec.name }
        val advertisement = toolPromptBuilder.advertisement()
        _diagnostics.value = ToolDiagnostics(
            pipelineEnabled = settings.toolCallingEnabled,
            registeredCount = tools.size,
            tools = tools.map { tool ->
                RegisteredToolInfo(
                    name = tool.spec.name,
                    description = tool.spec.description,
                    category = tool.spec.category.displayName,
                    enabled = settings.isToolEnabled(tool.spec.name),
                    requiresConfirmation = tool.spec.requiresConfirmation,
                    parametersJson = tool.spec.parameters.toString()
                )
            },
            // Matches ToolPlanner.allowedTools() exactly: every registered tool
            // that is not user-blocked while the pipeline is on.
            advertisedCount = if (settings.toolCallingEnabled) {
                tools.count { settings.isToolEnabled(it.spec.name) }
            } else 0,
            advertisementPreview = advertisement?.take(600) ?: "",
            capability = toolPlanner.capability.value
        )
    }

    /** Forces a live capability probe against the loaded model. */
    fun probeCapability() {
        viewModelScope.launch {
            val probe = toolPlanner.probeCapability()
            _diagnostics.value = _diagnostics.value.copy(capability = probe)
        }
    }

    fun clear() = traceStore.clear()
}
