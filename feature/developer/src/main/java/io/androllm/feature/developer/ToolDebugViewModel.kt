package io.androllm.feature.developer

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.androllm.core.tools.trace.ToolExecutionTrace
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Tool Debug ViewModel — mirrors the shared, bounded [ToolExecutionTraceStore]
 * so every chat/voice turn's tool calls (prompt → tool → args → status →
 * result → error → timing → LLM output) are visible in one place.
 */
@HiltViewModel
class ToolDebugViewModel @Inject constructor(
    private val traceStore: ToolExecutionTraceStore
) : ViewModel() {

    val traces: StateFlow<List<ToolExecutionTrace>> = traceStore.traces

    fun clear() = traceStore.clear()
}
