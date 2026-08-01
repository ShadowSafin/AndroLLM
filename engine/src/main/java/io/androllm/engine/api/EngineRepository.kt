package io.androllm.engine.api

import io.androllm.core.common.Result
import io.androllm.core.models.Model
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.MemoryStats
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level facade over [InferenceEngine] for the feature layer.
 * ViewModels never touch JNI or engine implementations directly.
 */
interface EngineRepository {

    /**
     * Lifecycle state of the engine.
     */
    val engineState: StateFlow<EngineState>

    /**
     * State of the current (or last) generation.
     */
    val generationState: StateFlow<GenerationState>

    /**
     * Real-time memory statistics of the loaded model.
     */
    val memoryStats: StateFlow<MemoryStats?>

    /**
     * Performance stats of the last generation.
     */
    val performanceStats: StateFlow<EngineStats?>

    /**
     * Static capabilities of the underlying engine.
     */
    val capabilities: EngineCapabilities

    /**
     * Initializes the engine (idempotent).
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Loads a model into the engine.
     */
    suspend fun loadModel(model: Model): Result<Unit>

    /**
     * Unloads the current model.
     */
    suspend fun unloadModel(): Result<Unit>

    /**
     * Renders a chat history with the loaded model's chat template, producing
     * the exact prompt string the model expects (BOS framing included).
     */
    suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean = true
    ): Result<String>

    /**
     * Generates a response for [prompt]. Streams tokens into [generationState]
     * and suspends until the generation finishes.
     */
    suspend fun generate(prompt: String, config: GenerationConfig = GenerationConfig()): Result<Unit>

    /**
     * Requests cancellation of an in-flight generation.
     */
    suspend fun cancelGeneration(): Result<Unit>

    /**
     * Full diagnostics of the loaded model and the last generation, or null
     * when unavailable. Used by the hidden debug panel.
     */
    suspend fun getDebugInfo(): Result<EngineDebugInfo?>

    /**
     * Releases all native resources.
     */
    fun release()
}

/**
 * State of a generation run.
 */
sealed interface GenerationState {
    data object Idle : GenerationState

    data class Generating(
        val prompt: String,
        val streamingText: String
    ) : GenerationState

    data class Completed(
        val text: String,
        val stats: EngineStats? = null
    ) : GenerationState

    data class Failed(
        val message: String,
        val partialText: String = ""
    ) : GenerationState

    data object Cancelled : GenerationState
}
