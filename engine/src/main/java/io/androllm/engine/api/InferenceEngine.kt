package io.androllm.engine.api

import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
import io.androllm.core.common.getOrThrow
import io.androllm.core.models.Model
import io.androllm.engine.models.BenchmarkResult
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineException
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.StreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll

/**
 * Abstraction over an on-device LLM runtime.
 *
 * The chat layer only depends on this interface. The sole production
 * implementation is [io.androllm.engine.llama.LlamaCppEngine] (llama.cpp),
 * but additional runtimes can be added without touching the UI.
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
     * Resets the engine's conversational state (messages + KV cache). Called on
     * conversation switch so a new conversation never continues from a stale
     * KV prefix (mirrors upstream ai_chat.cpp reset_long_term_states()).
     */
    suspend fun resetChat(): Result<Unit>

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
     * Runs a multi-turn chat generation from the FULL message history, keeping
     * the engine's conversational state (messages + KV cache) across turns —
     * the official llama.cpp diff-based multi-turn pattern. The engine diffs
     * the incoming history against its accumulated conversation and decodes
     * only the new messages' template diff; it falls back to a full re-render
     * whenever the history is not a strict continuation (edit, delete,
     * regenerate, new conversation, changed system prompt).
     *
     * The default implementation renders the prompt via [buildChatPrompt] and
     * delegates to [generate]; production engines override it with the native
     * stateful path.
     */
    suspend fun generateChat(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean = true,
        config: GenerationConfig = GenerationConfig()
    ): Result<String> = io.androllm.core.common.runCatching {
        val prompt = buildChatPrompt(messages, addAssistant).getOrThrow()
        generate(prompt, config).getOrThrow()
    }

    /**
     * Streaming variant of [generateChat]. Emits one element per token and
     * completes when generation finishes. Cancel the flow (or call [cancel])
     * to stop generation.
     */
    fun generateChatStream(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        config: GenerationConfig
    ): Flow<Result<StreamChunk>> = kotlinx.coroutines.flow.flow {
        val prompt = buildChatPrompt(messages, addAssistant).getOrNull()
        if (prompt == null) {
            emit(Result.Error(EngineException("Chat template failed")))
            return@flow
        }
        emitAll(tokenStream(prompt, config))
    }

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
