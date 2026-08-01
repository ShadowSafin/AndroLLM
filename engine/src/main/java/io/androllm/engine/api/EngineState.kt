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
    data class Failed(val message: String) : EngineState
}
