package io.androllm.engine.llama

import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.models.Model
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.jni.LlamaJniBridge
import io.androllm.engine.jni.NativeLibraryException
import io.androllm.engine.models.BackendType
import io.androllm.engine.models.BenchmarkResult
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.EngineCapabilities
import io.androllm.engine.models.EngineConfig
import io.androllm.engine.models.EngineDebugInfo
import io.androllm.engine.models.EngineException
import io.androllm.engine.models.EngineModelInfo
import io.androllm.engine.models.EngineStats
import io.androllm.engine.models.GenerationConfig
import io.androllm.engine.models.MemoryStats
import io.androllm.engine.models.ModelLoadConfig
import io.androllm.engine.models.StreamChunk
import io.androllm.engine.utils.ThreadManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native llama.cpp-backed implementation of [InferenceEngine].
 *
 * All blocking native calls run on [Dispatchers.Default] so the main
 * thread is never blocked. Streaming is pushed into a [callbackFlow].
 */
@Singleton
class LlamaCppEngine @Inject constructor() : InferenceEngine {

    @Volatile
    private var _capabilities = EngineCapabilities(
        name = "llama.cpp",
        version = LLAMA_VERSION,
        backend = BackendType.CPU,
        supportsGpuAcceleration = false,
        maxContextLength = DEFAULT_MAX_CONTEXT
    )
    override val capabilities: EngineCapabilities get() = _capabilities

    /**
     * Detected Vulkan availability after native library is loaded.
     */
    private var vulkanSupported: Boolean = false

    /**
     * Reason for Vulkan fallback to CPU, if any.
     */
    private var vulkanFallbackReason: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _stats = MutableStateFlow<EngineStats?>(null)
    override val stats: StateFlow<EngineStats?> = _stats.asStateFlow()

    @Volatile
    private var engineHandle: Long = 0L
    private var loadedModel: EngineModelInfo? = null
    private val generationActive = AtomicBoolean(false)
    private var promptCount = 0
    private var loadedSinceMs = 0L

    private val isNativeAvailable: Boolean by lazy {
        try {
            LlamaJniBridge.ensureLoaded()
            true
        } catch (e: NativeLibraryException) {
            _engineState.value = EngineState.Failed(e.message ?: "Native library unavailable")
            false
        }
    }

    override suspend fun initialize(config: EngineConfig): Result<Unit> = io.androllm.core.common.runCatching {
        if (!isNativeAvailable) return Result.error("Native engine unavailable")
        if (engineHandle != 0L) return Result.Success(Unit)

        withContext(Dispatchers.Default) {
            // Query Vulkan availability BEFORE creating the engine
            vulkanSupported = try {
                LlamaJniBridge.nativeVulkanAvailable()
            } catch (_: Exception) {
                false
            }
            
            if (!vulkanSupported) {
                vulkanFallbackReason = "Vulkan not available at native level - check build config (ANDROLLM_VULKAN=ON) and Vulkan SDK"
                android.util.Log.e("LlamaCppEngine", "⚠️ VULKAN NOT AVAILABLE: $vulkanFallbackReason")
            } else {
                vulkanFallbackReason = null
                android.util.Log.i("LlamaCppEngine", "✅ Vulkan GPU acceleration ENABLED")
            }
            
            // Vulkan is the required backend whenever native initialization
            // succeeds. A stale setting must never turn a supported Adreno
            // device into a CPU-only run.
            if (vulkanSupported && !config.useVulkan) {
                android.util.Log.w("LlamaCppEngine", "Ignoring useVulkan=false on a Vulkan-capable device")
            }
            val effectiveConfig = config.copy(
                useVulkan = vulkanSupported,
                threads = config.threads.coerceIn(1, ThreadManager.hardwareCores())
            )
            
            engineHandle = LlamaJniBridge.nativeCreate(
                json.encodeToString(
                    EngineConfig.serializer(),
                    effectiveConfig
                )
            )
            if (engineHandle == 0L) {
                throw EngineException("Failed to create native engine")
            }
            
            _capabilities = _capabilities.copy(
                supportsGpuAcceleration = vulkanSupported,
                backend = if (vulkanSupported) BackendType.VULKAN else BackendType.CPU
            )
            android.util.Log.i("LlamaCppEngine", "Engine initialized - Backend: ${_capabilities.backend}, Vulkan: $vulkanSupported")
        }
    }

    override fun isLoaded(): Boolean = loadedModel != null

    override fun getLoadedModel(): EngineModelInfo? = loadedModel

    override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> =
        io.androllm.core.common.runCatching {
            if (!isNativeAvailable) return Result.error("Native engine unavailable")
            check(engineHandle != 0L) { "Engine not initialized" }

            val filePath = model.filePath ?: return Result.error("Model has no file path")
            val effectiveLoadConfig = if (vulkanSupported) {
                // -1 is llama.cpp's all-layers request. Native code retries
                // lower counts only when VRAM prevents a full offload.
                config.copy(gpuLayers = -1)
            } else {
                config
            }
            _engineState.value = EngineState.Loading("Reading GGUF file...")
            withContext(Dispatchers.Default) {
                LlamaJniBridge.nativeLoadModel(
                    engineHandle,
                    filePath,
                    json.encodeToString(ModelLoadConfig.serializer(), effectiveLoadConfig)
                )
            }
            _engineState.value = EngineState.Loading("Initializing GPU resources...")

            val info = parseModelInfo(LlamaJniBridge.nativeModelInfo(engineHandle), model)
            loadedModel = info
            loadedSinceMs = System.currentTimeMillis()
            promptCount = 0

            // Warm-up phase
            _engineState.value = EngineState.WarmingUp("Compiling shaders...")
            try {
                withContext(Dispatchers.Default) {
                    LlamaJniBridge.nativeWarmUp(engineHandle)
                }
                _engineState.value = EngineState.WarmingUp("Finalizing...")
            } catch (e: Exception) {
                // Warm-up failure is non-fatal
                android.util.Log.w("LlamaCppEngine", "Warm-up failed: ${e.message}")
            }

            // Fetch initial memory stats
            val memStats = fetchMemoryStats()

            _engineState.value = EngineState.Ready(
                model = info,
                memoryStats = memStats,
                promptCount = 0,
                loadedSinceMs = loadedSinceMs
            )
            info
        }

    override suspend fun unloadModel(): Result<Unit> = io.androllm.core.common.runCatching {
        _engineState.value = EngineState.Unloading
        if (engineHandle != 0L) {
            try {
                withContext(Dispatchers.Default) { LlamaJniBridge.nativeUnload(engineHandle) }
            } catch (_: Exception) { }
        }
        loadedModel = null
        promptCount = 0
        loadedSinceMs = 0L
        _engineState.value = EngineState.Unloaded
    }

    override fun tokenStream(prompt: String, config: GenerationConfig): Flow<Result<StreamChunk>> =
        callbackFlow {
            if (!isNativeAvailable) {
                trySend(Result.Error(EngineException("Native engine unavailable")))
                close()
                return@callbackFlow
            }
            if (!isLoaded() || engineHandle == 0L) {
                trySend(Result.Error(EngineException("Model not loaded")))
                close()
                return@callbackFlow
            }
            if (!generationActive.compareAndSet(false, true)) {
                trySend(Result.Error(EngineException("Generation already in progress")))
                close()
                return@callbackFlow
            }
            var tokenCount = 0L
            val callback = LlamaJniBridge.TokenCallback { delta, finished ->
                if (delta.isNotEmpty()) tokenCount++
                trySend(
                    Result.Success(
                        StreamChunk(
                            delta = delta,
                            finished = finished,
                            tokenCount = tokenCount,
                            generatedTokens = tokenCount
                        )
                    )
                )
            }
            val job = launch(Dispatchers.Default) {
                promptCount++
                val currentModel = loadedModel
                if (currentModel != null) {
                    _engineState.value = EngineState.Generating(model = currentModel, promptNumber = promptCount)
                }
                try {
                    val statsJson = LlamaJniBridge.nativeGenerate(
                        engineHandle,
                        prompt,
                        json.encodeToString(GenerationConfig.serializer(), config),
                        callback
                    )
                    _stats.value = json.decodeFromString(EngineStats.serializer(), statsJson)
                } catch (e: CancellationException) {
                    LlamaJniBridge.nativeCancel(engineHandle)
                    throw e
                } catch (e: Throwable) {
                    trySend(Result.Error(EngineException(e.message ?: "Generation failed", e)))
                } finally {
                    generationActive.set(false)
                    val model = loadedModel
                    if (model != null) {
                        val memStats = fetchMemoryStats()
                        _engineState.value = EngineState.Ready(
                            model = model,
                            memoryStats = memStats,
                            promptCount = promptCount,
                            loadedSinceMs = loadedSinceMs
                        )
                    }
                    close()
                }
            }

            awaitClose {
                LlamaJniBridge.nativeCancel(engineHandle)
                job.cancel()
            }
        }

    override suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean
    ): Result<String> = io.androllm.core.common.runCatching {
        if (!isNativeAvailable) return Result.error("Native engine unavailable")
        check(isLoaded()) { "Model not loaded" }
        withContext(Dispatchers.Default) {
            LlamaJniBridge.nativeApplyChatTemplate(
                engineHandle,
                json.encodeToString(ListSerializer(ChatPromptMessage.serializer()), messages),
                addAssistant
            )
        }
    }

    /**
     * Renders the chat prompt with explicit control over the template's
     * `enable_thinking` flag. Pass `true` only for Qwen2.5/Qwen3 (or other
     * thinking-capable models); `false` is the safe default.
     */
    suspend fun buildChatPromptEx(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        enableThinking: Boolean
    ): Result<String> = io.androllm.core.common.runCatching {
        if (!isNativeAvailable) return Result.error("Native engine unavailable")
        check(isLoaded()) { "Model not loaded" }
        withContext(Dispatchers.Default) {
            LlamaJniBridge.nativeApplyChatTemplateEx(
                engineHandle,
                json.encodeToString(ListSerializer(ChatPromptMessage.serializer()), messages),
                addAssistant,
                enableThinking
            )
        }
    }

    override suspend fun generate(prompt: String, config: GenerationConfig): Result<String> =
        io.androllm.core.common.runCatching {
            if (!isNativeAvailable) return Result.error("Native engine unavailable")
            check(isLoaded()) { "Model not loaded" }
            // Same reentrancy guard as the streaming paths: two native runs on
            // one engine handle would race on the shared context/sampler.
            if (!generationActive.compareAndSet(false, true)) {
                return Result.error(EngineException("Generation already in progress"))
            }
            try {
                val sb = StringBuilder()
                val callback = LlamaJniBridge.TokenCallback { delta, _ -> sb.append(delta) }
                withContext(Dispatchers.Default) {
                    val statsJson = LlamaJniBridge.nativeGenerate(
                        engineHandle,
                        prompt,
                        json.encodeToString(GenerationConfig.serializer(), config),
                        callback
                    )
                    _stats.value = json.decodeFromString(EngineStats.serializer(), statsJson)
                }
                sb.toString()
            } finally {
                generationActive.set(false)
            }
        }

    /**
     * Multi-turn chat generation: sends the FULL message history to the native
     * engine, which diffs it against its accumulated conversation and decodes
     * only the new messages' template diff (official llama.cpp multi-turn
     * pattern). Non-streaming convenience; the streaming path is
     * [generateChatStream].
     */
    override suspend fun generateChat(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        config: GenerationConfig
    ): Result<String> = io.androllm.core.common.runCatching {
        if (!isNativeAvailable) return Result.error("Native engine unavailable")
        check(isLoaded()) { "Model not loaded" }
        // Same reentrancy guard as the streaming paths.
        if (!generationActive.compareAndSet(false, true)) {
            return Result.error(EngineException("Generation already in progress"))
        }
        try {
            val sb = StringBuilder()
            val callback = LlamaJniBridge.TokenCallback { delta, _ -> sb.append(delta) }
            withContext(Dispatchers.Default) {
                val statsJson = LlamaJniBridge.nativeGenerateChat(
                    engineHandle,
                    json.encodeToString(ListSerializer(ChatPromptMessage.serializer()), messages),
                    addAssistant,
                    json.encodeToString(GenerationConfig.serializer(), config),
                    callback
                )
                _stats.value = json.decodeFromString(EngineStats.serializer(), statsJson)
            }
            sb.toString()
        } finally {
            generationActive.set(false)
        }
    }

    override fun generateChatStream(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        config: GenerationConfig
    ): Flow<Result<StreamChunk>> = callbackFlow {
        if (!isNativeAvailable) {
            trySend(Result.Error(EngineException("Native engine unavailable")))
            close()
            return@callbackFlow
        }
        if (!isLoaded() || engineHandle == 0L) {
            trySend(Result.Error(EngineException("Model not loaded")))
            close()
            return@callbackFlow
        }
        if (!generationActive.compareAndSet(false, true)) {
            trySend(Result.Error(EngineException("Generation already in progress")))
            close()
            return@callbackFlow
        }
        var tokenCount = 0L
        val callback = LlamaJniBridge.TokenCallback { delta, finished ->
            if (delta.isNotEmpty()) tokenCount++
            trySend(
                Result.Success(
                    StreamChunk(
                        delta = delta,
                        finished = finished,
                        tokenCount = tokenCount,
                        generatedTokens = tokenCount
                    )
                )
            )
        }
        val job = launch(Dispatchers.Default) {
            promptCount++
            val currentModel = loadedModel
            if (currentModel != null) {
                _engineState.value = EngineState.Generating(model = currentModel, promptNumber = promptCount)
            }
            try {
                val statsJson = LlamaJniBridge.nativeGenerateChat(
                    engineHandle,
                    json.encodeToString(ListSerializer(ChatPromptMessage.serializer()), messages),
                    addAssistant,
                    json.encodeToString(GenerationConfig.serializer(), config),
                    callback
                )
                _stats.value = json.decodeFromString(EngineStats.serializer(), statsJson)
            } catch (e: CancellationException) {
                LlamaJniBridge.nativeCancel(engineHandle)
                throw e
            } catch (e: Throwable) {
                trySend(Result.Error(EngineException(e.message ?: "Chat generation failed", e)))
            } finally {
                generationActive.set(false)
                val model = loadedModel
                if (model != null) {
                    val memStats = fetchMemoryStats()
                    _engineState.value = EngineState.Ready(
                        model = model,
                        memoryStats = memStats,
                        promptCount = promptCount,
                        loadedSinceMs = loadedSinceMs
                    )
                }
                close()
            }
        }

        awaitClose {
            LlamaJniBridge.nativeCancel(engineHandle)
            job.cancel()
        }
    }

    override fun cancel(): Result<Unit> = io.androllm.core.common.runCatching {
        if (!isNativeAvailable) return Result.error("Native engine unavailable")
        if (engineHandle != 0L) {
            LlamaJniBridge.nativeCancel(engineHandle)
        }
        generationActive.set(false)
    }

    override fun benchmark(iterations: Int): Flow<Result<BenchmarkResult>> = callbackFlow {
        if (!isLoaded() || engineHandle == 0L) {
            trySend(Result.Error(EngineException("Model not loaded")))
            close()
            return@callbackFlow
        }

        val callback = LlamaJniBridge.TokenCallback { _, _ -> }

        val job = launch(Dispatchers.Default) {
            try {
                val resultJson = LlamaJniBridge.nativeBenchmark(engineHandle, iterations, callback)
                trySend(
                    Result.Success(
                        json.decodeFromString(BenchmarkResult.serializer(), resultJson)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                trySend(Result.Error(EngineException(e.message ?: "Benchmark failed", e)))
            } finally {
                close()
            }
        }

        awaitClose { job.cancel() }
    }

    override fun release() {
        if (engineHandle != 0L) {
            try {
                LlamaJniBridge.nativeRelease(engineHandle)
            } catch (_: Exception) { }
            engineHandle = 0L
        }
        loadedModel = null
        promptCount = 0
        loadedSinceMs = 0L
        generationActive.set(false)
        vulkanSupported = false
        vulkanFallbackReason = null
        _capabilities = _capabilities.copy(supportsGpuAcceleration = false, backend = BackendType.CPU)
        _engineState.value = EngineState.Unloaded
        _stats.value = null
    }

    private fun fetchMemoryStats(): MemoryStats? {
        return try {
            if (engineHandle == 0L) return null
            val statsJson = LlamaJniBridge.nativeGetMemoryStats(engineHandle)
            if (statsJson.isBlank() || statsJson == "{}") return null
            json.decodeFromString(MemoryStats.serializer(), statsJson)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseModelInfo(infoJson: String, model: Model): EngineModelInfo {
        if (infoJson.isBlank() || infoJson == "null") {
            return EngineModelInfo(
                id = model.id,
                filePath = model.filePath ?: "",
                contextLength = model.contextLength,
                vocabSize = 0,
                backend = capabilities.backend,
                quantization = model.quantization
            )
        }
        val obj = json.parseToJsonElement(infoJson).jsonObject
        val backend = obj["backend"]?.jsonPrimitive?.contentOrNull?.let { raw ->
            BackendType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: capabilities.backend
        val gpuLayers = obj["gpuLayers"]?.jsonPrimitive?.intOrNull ?: 0
        val cpuLayers = obj["cpuLayers"]?.jsonPrimitive?.intOrNull ?: 0
        val nLayers = obj["n_layers"]?.jsonPrimitive?.intOrNull ?: 0
        val chatTemplate = obj["chatTemplate"]?.jsonPrimitive?.contentOrNull
        
        android.util.Log.i("LlamaCppEngine", "Model info - Backend: $backend, GPU Layers: $gpuLayers/$nLayers, CPU Layers: $cpuLayers")
        if (chatTemplate != null) {
            android.util.Log.i("LlamaCppEngine", "Chat template: $chatTemplate")
        }
        
        return EngineModelInfo(
            id = model.id,
            filePath = model.filePath ?: "",
            contextLength = obj["n_ctx_train"]?.jsonPrimitive?.intOrNull ?: model.contextLength,
            vocabSize = obj["n_vocab"]?.jsonPrimitive?.intOrNull ?: 0,
            backend = backend,
            quantization = model.quantization,
            chatTemplate = chatTemplate,
            architecture = obj["architecture"]?.jsonPrimitive?.contentOrNull ?: "",
            tokenizerModel = obj["tokenizerModel"]?.jsonPrimitive?.contentOrNull ?: "",
            generalName = obj["generalName"]?.jsonPrimitive?.contentOrNull ?: "",
            kvType = obj["kvType"]?.jsonPrimitive?.contentOrNull ?: "",
            nBatch = obj["nBatch"]?.jsonPrimitive?.intOrNull ?: 0,
            nUbatch = obj["nUbatch"]?.jsonPrimitive?.intOrNull ?: 0,
            nThreads = obj["nThreads"]?.jsonPrimitive?.intOrNull ?: 0,
            flashAttn = obj["flashAttn"]?.jsonPrimitive?.contentOrNull ?: "",
            templateReady = obj["templateReady"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            templateError = obj["templateError"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    override suspend fun getDebugInfo(): Result<EngineDebugInfo?> = io.androllm.core.common.runCatching {
        if (!isNativeAvailable || engineHandle == 0L || !isLoaded()) return Result.Success(null)
        val infoJson = withContext(Dispatchers.Default) {
            LlamaJniBridge.nativeGetDebugInfo(engineHandle)
        }
        if (infoJson.isBlank() || infoJson == "{}") null else json.decodeFromString(EngineDebugInfo.serializer(), infoJson)
    }

    companion object {
        const val LLAMA_VERSION = "0.0.0"
        const val DEFAULT_MAX_CONTEXT = 32768
    }
}
