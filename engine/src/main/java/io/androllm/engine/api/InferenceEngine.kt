package io.androllm.engine.api

import io.androllm.core.common.Result
import io.androllm.core.models.Model
import io.androllm.engine.models.BenchmarkResult
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.StreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over an on-device LLM runtime.
 *
 * The chat layer only depends on this interface, so additional runtimes
 * (ONNX, MLC, MediaPipe, TFLite) can be added without touching the UI.
 */
interface InferenceEngine {

    /**
     * Static capabilities of this engine implementation.
     */
    val capabilities: EngineCapabilities

    /**
     * State of the native engine lifecycle.
     */
    val engineState: Flow<EngineState>

    /**
     * Performance stats of the last generation.
     */
    val stats: Flow<EngineStats?>

    /**
     * Initializes the runtime (loads native libraries, resolves backends).
     */
    suspend fun initialize(config: EngineConfig): Result<Unit>

    /**
     * True when a model is currently loaded.
     */
    fun isLoaded(): Boolean

    /**
     * Returns the currently loaded model, or null.
     */
    fun getLoadedModel(): EngineModelInfo?

    /**
     * Loads a GGUF model from disk.
     */
    suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo>

    /**
     * Unloads the current model and frees native memory.
     */
    suspend fun unloadModel(): Result<Unit>

    /**
     * Streams tokens for the given prompt. The flow emits one element per token
     * and completes when generation finishes. Cancel the flow (or call [cancel])
     * to stop generation.
     */
    fun tokenStream(prompt: String, config: GenerationConfig): Flow<Result<StreamChunk>>

    /**
     * Renders a chat history with the loaded model's GGUF chat template.
     * Returns the rendered prompt, or an error when the model has no
     * supported chat template.
     */
    suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean = true
    ): Result<String>

    /**
     * Runs a full generation and returns the final result. Prefer [tokenStream]
     * for interactive use.
     */
    suspend fun generate(prompt: String, config: GenerationConfig): Result<String>

    /**
     * Requests cancellation of an in-flight generation.
     */
    fun cancel(): Result<Unit>

    /**
     * Runs a quick benchmark against the loaded model.
     */
    fun benchmark(iterations: Int): Flow<Result<BenchmarkResult>>

    /**
     * Returns full diagnostics of the loaded model and the last generation
     * (chat template, tokenizer framing, token IDs, first-token latency), or
     * null when unavailable. Used by the hidden debug panel.
     */
    suspend fun getDebugInfo(): Result<EngineDebugInfo?>

    /**
     * Releases all native resources. The engine cannot be used afterwards
     * without a new [initialize].
     */
    fun release()
}
