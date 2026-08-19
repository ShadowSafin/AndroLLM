package io.androllm.engine.api

import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.MemoryStats

/**
 * 7-state lifecycle of the persistent inference engine.
 */
sealed interface EngineState {
    data object Unloaded : EngineState
    data class Loading(val stage: String = "Initializing...") : EngineState
    data class WarmingUp(val step: String = "Compiling shaders...") : EngineState
    data class Ready(
        val model: EngineModelInfo,
        val memoryStats: MemoryStats? = null,
        val promptCount: Int = 0,
        val loadedSinceMs: Long = 0L
    ) : EngineState
    data class Generating(
        val model: EngineModelInfo,
        val promptNumber: Int = 0
    ) : EngineState
    data object Unloading : EngineState

    /**
     * A load or runtime failure with structured diagnostics.
     *
     * [message] is the human-readable reason. [stage] names the failing load
     * phase ("validate" — pre-load artifact checks, "initialize" — native
     * engine build, "compatibility" — family/tokenizer contract, "selftest" —
     * post-load coherence probe, "load" — generic). [suggestion] is an
     * actionable remediation hint the UI can show under the error, and
     * [retryable] tells the UI whether a Retry button can succeed (false for
     * e.g. a model that can never load on this device).
     */
    data class Failed(
        val message: String,
        val stage: String = "load",
        val suggestion: String? = null,
        val retryable: Boolean = true
    ) : EngineState
}
