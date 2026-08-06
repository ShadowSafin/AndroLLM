package io.androllm.engine.api

import io.androllm.core.common.Result
import io.androllm.core.common.getOrThrow
import io.androllm.core.models.Model
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.MemoryStats
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.StreamChunk
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.ChatPromptMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * No-op placeholder engine used in tests and as a safe fallback
 * when the native library is unavailable.
 */
class NoOpInferenceEngine : InferenceEngine {

    override val capabilities = EngineCapabilities(
        name = "AndroLLM Engine (Placeholder)",
        version = "1.0.0",
        backend = BackendType.CPU
    )

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val engineState = _engineState.asStateFlow()

    private val _stats = MutableStateFlow<EngineStats?>(null)
    override val stats = _stats.asStateFlow()

    private var loadedModel: EngineModelInfo? = null

    override suspend fun initialize(config: EngineConfig): Result<Unit> = io.androllm.core.common.runCatching {
        if (_engineState.value == EngineState.Unloaded) {
            _engineState.value = EngineState.Loading("Loading...")
        }
    }

    override fun isLoaded(): Boolean = loadedModel != null

    override fun getLoadedModel(): EngineModelInfo? = loadedModel

    override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> =
        io.androllm.core.common.runCatching {
            _engineState.value = EngineState.Loading("Loading...")
            val info = EngineModelInfo(
                id = model.id,
                filePath = model.filePath ?: "",
                contextLength = config.contextLength,
                vocabSize = 0,
                backend = BackendType.CPU,
                quantization = model.quantization
            )
            loadedModel = info
            _engineState.value = EngineState.Ready(
                model = info,
                memoryStats = null,
                promptCount = 0,
                loadedSinceMs = System.currentTimeMillis()
            )
            info
        }

    override suspend fun unloadModel(): Result<Unit> = io.androllm.core.common.runCatching {
        loadedModel = null
        _engineState.value = EngineState.Unloaded
    }

    override fun tokenStream(prompt: String, config: GenerationConfig): kotlinx.coroutines.flow.Flow<Result<StreamChunk>> =
        kotlinx.coroutines.flow.flow {
            emit(Result.Success(StreamChunk("This is a ", false)))
            kotlinx.coroutines.delay(50)
            emit(Result.Success(StreamChunk("placeholder ", false)))
            kotlinx.coroutines.delay(50)
            emit(Result.Success(StreamChunk("response. ", false)))
            kotlinx.coroutines.delay(50)
            emit(Result.Success(StreamChunk("LLM inference is provided by the native llama.cpp engine.", true, 4)))
        }

    override suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean
    ): Result<String> = io.androllm.core.common.runCatching {
        buildString {
            for (message in messages) {
                append("<|im_start|>${message.role}\n${message.content}<|im_end|>\n")
            }
            if (addAssistant) append("<|im_start|>assistant\n")
        }
    }

    override suspend fun generate(prompt: String, config: GenerationConfig): Result<String> =
        io.androllm.core.common.runCatching {
            "This is a placeholder response. LLM inference is provided by the native llama.cpp engine."
        }

    override fun cancel(): Result<Unit> = Result.Success(Unit)

    override fun benchmark(iterations: Int): kotlinx.coroutines.flow.Flow<Result<io.androllm.engine.models.BenchmarkResult>> =
        kotlinx.coroutines.flow.flow {
            emit(
                Result.Success(
                    io.androllm.engine.models.BenchmarkResult(
                        iterations = iterations,
                        averageTokensPerSecond = 10f,
                        bestTokensPerSecond = 12f,
                        averagePromptTokensPerSecond = 50f
                    )
                )
            )
        }

    override suspend fun getDebugInfo(): Result<EngineDebugInfo?> = Result.Success(null)

    override fun release() {
        loadedModel = null
        _engineState.value = EngineState.Unloaded
    }
}

/**
 * Default [EngineRepository] backed by an [InferenceEngine].
 */
@Singleton
class DefaultEngineRepository @Inject constructor(
    private val engine: InferenceEngine
) : EngineRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    override val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val _performanceStats = MutableStateFlow<EngineStats?>(null)
    override val performanceStats: StateFlow<EngineStats?> = _performanceStats.asStateFlow()

    private val _memoryStats = MutableStateFlow<MemoryStats?>(null)
    override val memoryStats: StateFlow<MemoryStats?> = _memoryStats.asStateFlow()

    /**
     * Serializes [generate] and [generateQuiet] so interactive chat generation
     * and background memory extraction/summarization never overlap on the
     * single native engine handle.
     */
    private val generationMutex = Mutex()

    override val capabilities: EngineCapabilities
        get() = engine.capabilities

    init {
        engine.engineState
            .onEach { state ->
                _engineState.value = state
                if (state is EngineState.Ready) {
                    _memoryStats.value = state.memoryStats
                }
            }
            .catch { _engineState.value = EngineState.Failed(it.message ?: "Engine error") }
            .launchIn(scope)

        engine.stats
            .onEach { _performanceStats.value = it }
            .launchIn(scope)
    }

    override suspend fun initialize(): Result<Unit> = io.androllm.core.common.runCatching {
        engine.initialize(EngineConfig())
    }

    override suspend fun loadModel(model: Model): Result<Unit> = io.androllm.core.common.runCatching {
        engine.loadModel(model, ModelLoadConfig())
    }

    override suspend fun unloadModel(): Result<Unit> = io.androllm.core.common.runCatching {
        engine.unloadModel()
        _engineState.value = EngineState.Unloaded
        _memoryStats.value = null
    }

    override suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean
    ): Result<String> = engine.buildChatPrompt(messages, addAssistant)

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig
    ): Result<Unit> = generationMutex.withLock {
        if (!engine.isLoaded()) {
            _generationState.value = GenerationState.Failed("Model not loaded")
            return Result.error("Model not loaded")
        }

        _generationState.value = GenerationState.Generating(prompt = prompt, streamingText = "", generatedTokens = 0L)

        var fullText = ""
        var lastEmitTime = 0L
        var tokenCount = 0L
        try {
            engine.tokenStream(prompt, config)
                .onEach { result ->
                    when (result) {
                        is Result.Success -> {
                            val chunk = result.data
                            if (chunk.delta.isNotEmpty() && !chunk.finished) {
                                fullText += chunk.delta
                                // Prefer the native token counter when the backend reports it;
                                // otherwise count emitted deltas (1 delta = 1 token piece).
                                if (chunk.generatedTokens > 0) tokenCount = chunk.generatedTokens else tokenCount++
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= 16L) {
                                    lastEmitTime = now
                                    _generationState.value = GenerationState.Generating(
                                        prompt = prompt,
                                        streamingText = fullText,
                                        generatedTokens = tokenCount
                                    )
                                }
                            }
                        }

                        is Result.Error -> throw result.exception
                    }
                }
                .collect()

            val stats = engine.stats.firstOrNull()
            _generationState.value = GenerationState.Completed(text = fullText, stats = stats)
            Result.Success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _generationState.value = GenerationState.Failed(
                message = e.message ?: "Generation failed",
                partialText = fullText
            )
            Result.Error(e)
        }
    }

    override suspend fun generateQuiet(
        prompt: String,
        config: GenerationConfig
    ): Result<String> = try {
        generationMutex.withLock {
            if (!engine.isLoaded()) {
                Result.error("Model not loaded")
            } else {
                engine.generate(prompt, config)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Background pipelines (memory extraction) are cancelled when a new
        // chat turn supersedes them — that must propagate as cancellation,
        // never be swallowed into Result.Error.
        throw e
    } catch (e: Exception) {
        Result.Error(e)
    }

    override suspend fun cancelGeneration(): Result<Unit> = io.androllm.core.common.runCatching {
        engine.cancel()
        _generationState.value = GenerationState.Cancelled
    }

    override suspend fun getDebugInfo(): Result<EngineDebugInfo?> = engine.getDebugInfo()

    override fun release() {
        engine.release()
        _engineState.value = EngineState.Unloaded
        _generationState.value = GenerationState.Idle
        _memoryStats.value = null
    }
}
