package io.androllm.engine.api

import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
import io.androllm.core.common.getOrThrow
import io.androllm.core.models.Model
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.backend.BackendSelector
import io.androllm.engine.utils.CoherenceChecker
import io.androllm.engine.utils.CoherenceResult
import io.androllm.engine.models.BackendBenchmarkResult
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
import io.androllm.engine.core.OutputSanitizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.atomic.AtomicBoolean        /**
         * Tokenizer control markers that must never appear in decoded output
         * (see LiteRtLmEngine.stripControlTokens). Used by the load-time
         * self-test to reject mismatched containers.
         */
        private val CONTROL_TOKEN_MARKERS = listOf(
            "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|end_of_text|>",
            "<|end_of_turn|>", "<think>", "</think>", "<bos>", "<eos>", "<pad>", "<unk>",
            "<start_of_turn>", "<end_of_turn>"
        )

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

    private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
    override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()

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
            emit(Result.Success(StreamChunk("LLM inference is provided by the local LiteRT-LM engine.", true, 4)))
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
            "This is a placeholder response. LLM inference is provided by the local LiteRT-LM engine."
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

    override suspend fun resetChat(): Result<Unit> = Result.Success(Unit)

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

    override fun takeLastNativeToolCalls(): List<io.androllm.engine.core.NativeToolCall> =
        engine.takeLastNativeToolCalls()

    private val _memoryStats = MutableStateFlow<MemoryStats?>(null)
    override val memoryStats: StateFlow<MemoryStats?> = _memoryStats.asStateFlow()

    private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
    override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()

    /**
     * Serializes [generate] and [generateQuiet] so interactive chat generation
     * and background memory extraction/summarization never overlap on the
     * single native engine handle.
     */
    private val generationMutex = Mutex()

    /**
     * Set by [cancelGeneration] while a [generate] run is in flight. The native
     * loop exits cleanly on cancel (it does not throw), so without this flag a
     * cancelled run would be republished as [GenerationState.Completed] and its
     * partial text persisted as a full assistant response — corrupting the
     * context for the next prompt.
     */
    private val cancelRequested = AtomicBoolean(false)

    override val capabilities: EngineCapabilities
        get() = engine.capabilities

    init {
        // Forward the startup probe from the engine so the adaptive settings
        // UI and the developer benchmark stay in sync with it.
        scope.launch {
            engine.backendCapabilities.collect { _backendCapabilities.value = it }
        }

        engine.engineState
            .onEach { state ->
                _engineState.value = state
            }
            .catch { _engineState.value = EngineState.Failed(it.message ?: "Engine error") }
            .launchIn(scope)

        engine.stats
            .onEach { _performanceStats.value = it }
            .launchIn(scope)

        // The engine owns the polling cadence because only it knows whether a
        // native model is resident. Forward every live snapshot to feature UIs.
        engine.memoryStats
            .onEach { _memoryStats.value = it }
            .launchIn(scope)
    }

    override suspend fun initialize(): Result<Unit> = io.androllm.core.common.runCatching {
        engine.initialize(EngineConfig())
    }

    override suspend fun loadModel(
        model: Model,
        config: ModelLoadConfig
    ): Result<Unit> = generationMutex.withLock {
        io.androllm.core.common.runCatching {
            engine.loadModel(model, config).getOrThrow()

            // Post-load self-test: before the model is advertised as Ready,
            // verify it actually produces coherent text. A corrupt
            // tokenizer/weights ("valid container but outputs gibberish") is
            // unloaded immediately with a clear reason instead of poisoning the
            // chat with garbage. Held under generationMutex so the probe can
            // never collide with an in-flight chat generation (which would
            // false-fail the probe) and the native context swap never races an
            // active decode.
            android.util.Log.i(
                TAG,
                "Model loaded: ${model.name} (${model.filePath}) context=${
                    config.contextLength.takeIf { it > 0 } ?: io.androllm.core.common.AppConstants.Model.DEFAULT_CONTEXT_LENGTH
                } backend=${engine.capabilities.backend}"
            )

            if (config.runSelfTest) {
                when (val probeResult = probeCoherence()) {
                    is CoherenceResult.Pass -> Unit
                    is CoherenceResult.Fail -> {
                        android.util.Log.e(TAG, "Model self-test failed: ${probeResult.reason}")
                        teardownFailedModel(probeResult.reason)
                        if (config.gpuLayers != 0) {
                            // The GPU backend (Vulkan) produced degenerate text
                            // on this device/driver. Per the local-LLM spec,
                            // Vulkan must fail safely and fall back to CPU — a
                            // model is refused only if BOTH backends fail.
                            // Bounded: cpuConfig.gpuLayers == 0 cannot recurse.
                            android.util.Log.w(TAG, "GPU self-test failed — falling back to CPU (gpuLayers=0)")
                            _engineState.value = EngineState.Loading("GPU output check failed — retrying on CPU")
                            engine.loadModel(model, config.copy(gpuLayers = 0)).getOrThrow()
                            when (val cpuResult = probeCoherence()) {
                                is CoherenceResult.Pass -> Unit
                                is CoherenceResult.Fail -> {
                                    android.util.Log.e(TAG, "Model self-test failed on CPU too: ${cpuResult.reason}")
                                    teardownFailedModel(cpuResult.reason)
                                    _engineState.value = EngineState.Failed("Model self-test failed: ${cpuResult.reason}")
                                    return Result.error("Model self-test failed: ${cpuResult.reason}")
                                }
                            }
                        } else {
                            _engineState.value = EngineState.Failed("Model self-test failed: ${probeResult.reason}")
                            return Result.error("Model self-test failed: ${probeResult.reason}")
                        }
                    }
                }
            }
        }
    }

    override suspend fun unloadModel(): Result<Unit> = generationMutex.withLock {
        io.androllm.core.common.runCatching {
            engine.unloadModel()
            _engineState.value = EngineState.Unloaded
            _memoryStats.value = null
        }
    }

    /**
     * Bounded coherence probe used by the load-time self-test. A broken model
     * must fail FAST, not hang the load forever: if the probe produces no
     * tokens within [SELF_TEST_TIMEOUT_MS], the load is rejected with the
     * stall reason instead of an opaque "no output".
     */
    private suspend fun probeCoherence(): CoherenceResult {
        var probeStalled = false
        val probeText = try {
            withTimeout(SELF_TEST_TIMEOUT_MS) {
                engine.generate(
                    "Hi",
                    GenerationConfig(temperature = 0f, maxTokens = 12, seed = -1, debugTokenLogging = true)
                ).getOrNull()
            }
        } catch (e: TimeoutCancellationException) {
            probeStalled = true
            android.util.Log.e(TAG, "Self-test probe stalled: no tokens within ${SELF_TEST_TIMEOUT_MS}ms")
            null
        } catch (e: Exception) {
            null
        }
        if (probeStalled) {
            return CoherenceResult.Fail(
                "model did not produce any tokens within ${SELF_TEST_TIMEOUT_MS / 1000}s — inference is broken on this backend"
            )
        }
        val coherence = CoherenceChecker.check(probeText)
        if (coherence !is CoherenceResult.Pass) return coherence
        // Template/tokenizer mismatch guard: a model that decodes control
        // tokens (im_start / think / bos / ...) instead of assistant text is a
        // broken or mismatched container (e.g. Qwen3 weights with a Gemma
        // template). It must never reach the chat — reject it at load with the
        // real reason instead of streaming <|im_start|> to the UI. Same marker
        // set the engine strips defensively at the stream boundary
        // (LiteRtLmEngine.stripControlTokens).
        val leaked = CONTROL_TOKEN_MARKERS.firstOrNull { probeText?.contains(it) == true }
        if (leaked != null) {
            android.util.Log.e(TAG, "Self-test probe emitted control token '$leaked': ${probeText?.take(80)}")
            return CoherenceResult.Fail(
                "model decodes the '$leaked' control token instead of assistant text — " +
                    "the chat template and tokenizer do not match this artifact (broken or repacked container). " +
                    "Re-download the model from the catalog."
            )
        }
        return coherence
    }

    /**
     * Tears down a model whose self-test failed. Cancel FIRST: an interruptible
     * hang exits its native loop at the next cancel check; destroy() then frees
     * the context without racing a live decode. Also clears the engine's
     * Kotlin-side generationActive flag so the next load attempt isn't wedged
     * on "generation already in progress".
     */
    private suspend fun teardownFailedModel(reason: String) {
        engine.cancel()
        runCatching { engine.unloadModel() }
        android.util.Log.w(TAG, "Unloaded model after failed self-test: $reason")
    }

    override suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean
    ): Result<String> = engine.buildChatPrompt(messages, addAssistant)

    override suspend fun generate(
        prompt: String,
        config: GenerationConfig
    ): Result<Unit> = generationMutex.withLock {
        // A fresh run never inherits a stale cancel (e.g. one requested while
        // the engine was idle or waiting behind background work).
        cancelRequested.set(false)

        if (!engine.isLoaded()) {
            _generationState.value = GenerationState.Failed("Model not loaded")
            return Result.error("Model not loaded")
        }

        _generationState.value = GenerationState.Generating(prompt = prompt, streamingText = "", generatedTokens = 0L)
        android.util.Log.i(TAG, "Generation started: promptLen=${prompt.length} maxTokens=${config.maxTokens}")

        // PERFORMANCE: append into a StringBuilder instead of `fullText += delta`.
        // Pre-size to reduce internal array resizing during streaming.
        val fullTextBuilder = StringBuilder(2048)
        val displayBuilder = StringBuilder(2048)
        var lastEmitTime = 0L
        var tokenCount = 0L
        // Stall detection: a decode that produces no token (hung GPU fence, dead
        // sampler) must never spin forever. The first-token watchdog cancels the
        // native loop after [stallTimeoutMs] without a token; the hard ceiling
        // (scaled to the requested token budget) escapes a run that never ends.
        // See [generateChat].
        val firstTokenTimeoutMs = stallTimeoutMs(prompt.length)
        val hardTimeoutMs = hardGenerationTimeoutMs(config.maxTokens)
        val firstTokenSeen = CompletableDeferred<Unit>()
        val stallDetected = AtomicBoolean(false)
        try {
            withTimeout(hardTimeoutMs) {
                coroutineScope {
                    // CRITICAL: the watchdog runs on the repository's OWN
                    // multi-threaded scope, NOT the caller's dispatcher. The
                    // native decode loop executes inline on the collection
                    // thread (channelFlow) — if a single-threaded caller
                    // (e.g. a Main-scope caller that forgot to dispatch) is
                    // blocked in JNI, a watchdog launched in the caller's
                    // context could never be scheduled and stall detection
                    // would silently never fire ("no output, no timeout").
                    val watchdog = scope.launch {
                        try {
                            withTimeout(firstTokenTimeoutMs) { firstTokenSeen.await() }
                        } catch (e: TimeoutCancellationException) {
                            stallDetected.set(true)
                            android.util.Log.e(TAG, "STALL: no token within ${firstTokenTimeoutMs}ms — cancelling generation")
                            engine.cancel()
                        }
                    }
                    try {
                        engine.tokenStream(prompt, config)
                            .onEach { result ->
                                when (result) {
                                    is Result.Success -> {
                                        val chunk = result.data
                                        if (chunk.delta.isNotEmpty() && !chunk.finished) {
                                            if (tokenCount == 0L) firstTokenSeen.complete(Unit)
                                            // Prefer the native token counter when the backend reports it;
                                            // otherwise count emitted deltas (1 delta = 1 token piece).
                                            if (chunk.generatedTokens > 0) tokenCount = chunk.generatedTokens else tokenCount++
                                            // Reasoning deltas stream for live progress but are never
                                            // part of the final text (assistant message = answer only).
                                            displayBuilder.append(chunk.delta)
                                            if (!chunk.isThinking) fullTextBuilder.append(chunk.delta)
                                            val now = System.currentTimeMillis()
                                            if (now - lastEmitTime >= 16L) {
                                                lastEmitTime = now
                                                _generationState.value = GenerationState.Generating(
                                                    prompt = prompt,
                                                    streamingText = OutputSanitizer.streamingReady(displayBuilder.toString()),
                                                    generatedTokens = tokenCount
                                                )
                                            }
                                        }
                                    }

                                    is Result.Error -> throw result.exception
                                }
                            }
                            .collect()
                    } finally {
                        watchdog.cancel()
                    }
                }
            }

            if (stallDetected.get()) {
                _generationState.value = GenerationState.Failed(
                    message = "No tokens were generated within ${firstTokenTimeoutMs / 1000}s — inference stalled. " +
                        "Try a smaller model or switch the backend.",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("Generation stalled: no first token")
            }
            if (cancelRequested.getAndSet(false)) {
                // A cancel was requested mid-flight (Stop pressed). The native
                // loop exits cleanly, so completion must NOT be published here:
                // a cancelled run never surfaces as a full response and its
                // partial text is never fed back into the next prompt.
                android.util.Log.w(TAG, "Generation cancelled by user after $tokenCount tokens")
                _generationState.value = GenerationState.Cancelled
                return Result.Success(Unit)
            }
            val stats = engine.stats.firstOrNull()
            if (stats.isCorruptedStop()) {
                android.util.Log.e(TAG, "Generation corrupted: stopReason=${stats?.stopReason}")
                _generationState.value = GenerationState.Failed(
                    message = "Decode error — try again",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("Decode error")
            }
            // NO-PROGRESS TERMINAL GUARD: the native loop now fails a run that
            // performs many decode iterations without producing any output
            // (broken tokenizer / corrupt weights / sampler returning only
            // control tokens). The native side throws for this case, so this is
            // defense-in-depth for a stats blob that reaches us instead — it
            // must surface the no-progress failure, never a blank completion.
            if (stats?.stopReason == "no_progress") {
                android.util.Log.e(TAG, "Generation no-progress: stopReason=${stats?.stopReason}")
                _generationState.value = GenerationState.Failed(
                    message = "No-progress generation loop detected: the engine decoded repeatedly but produced no output. " +
                        "Try a smaller model, disable the GPU, or re-download the model.",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("No-progress generation loop detected")
            }
            // ZERO-OUTPUT TERMINAL GUARD — the exact "stuck generating" cycle.
            // A run that produced NO output text is only a legitimate completion
            // when the model hit EOS/EOG on the very first sample. Every other
            // outcome — an unknown/empty stop reason (the native recovery
            // ladder exhausted its retries and returned a bare "{}" stats blob,
            // which decodes to an empty EngineStats whose stopReason is ""), a
            // silent decode failure, or a zero-token "max_tokens" — means the
            // backend never produced a response. Publishing Completed("") would
            // leave the chat with a vanished assistant placeholder and NO error
            // (the UI appears to loop forever), and the next send/regenerate
            // would re-enter the same failing cycle. Fail visibly instead.
            // NOTE: the native bridge reports end-of-generation as "eog"
            // (llama.cpp convention) — treated identically to "eos" here so an
            // immediate-EOG empty completion is legitimate, not a failure.
            if (fullTextBuilder.isEmpty() && stats?.stopReason != "eos" && stats?.stopReason != "eog") {
                android.util.Log.e(
                    TAG,
                    "Generation produced no tokens (stopReason=${stats?.stopReason ?: "?"}) — surfacing as failure"
                )
                _generationState.value = GenerationState.Failed(
                    message = "The model produced no tokens (stop reason: ${stats?.stopReason?.ifBlank { "unknown" } ?: "unknown"}). " +
                        "Inference failed on this backend — try a smaller model, disable the GPU, or re-download the model.",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("No tokens generated")
            }
            android.util.Log.i(
                TAG,
                "Generation finished: tokens=$tokenCount stopReason=${stats?.stopReason ?: "?"} tps=${stats?.tokensPerSecond ?: 0f} timeMs=${stats?.totalTimeMs ?: 0}"
            )
            _generationState.value = GenerationState.Completed(text = OutputSanitizer.sanitize(fullTextBuilder.toString()), stats = stats)
            Result.Success(Unit)
        } catch (e: TimeoutCancellationException) {
            android.util.Log.e(
                TAG,
                "Generation timed out after ${hardTimeoutMs / 1000}s (${fullTextBuilder.length} chars, $tokenCount tokens)"
            )
            _generationState.value = GenerationState.Failed(
                message = "Generation exceeded the ${hardTimeoutMs / 1000}s time limit and was stopped.",
                partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
            )
            Result.error("Generation timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
android.util.Log.e(TAG, "Generation failed: ${e.message}", e)
            _generationState.value = GenerationState.Failed(
                message = e.message ?: "Generation failed",
                partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
            )
            Result.Error(e)
        }
    }

    override suspend fun generateChat(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        config: GenerationConfig
    ): Result<Unit> = generationMutex.withLock {
        cancelRequested.set(false)

        if (!engine.isLoaded()) {
            _generationState.value = GenerationState.Failed("Model not loaded")
            return Result.error("Model not loaded")
        }

        val lastUser = messages.lastOrNull { it.role == "user" }?.content ?: "(chat)"
        _generationState.value = GenerationState.Generating(prompt = lastUser, streamingText = "", generatedTokens = 0L)
        android.util.Log.i(TAG, "Chat generation started: messages=${messages.size} lastUserLen=${lastUser.length}")

        // PERFORMANCE: StringBuilder append instead of `fullText += delta` (see
        // [generate]) to avoid the O(n²) String-copy garbage spike after the
        // generation finishes. Pre-size to reduce resizing during streaming.
        val fullTextBuilder = StringBuilder(2048)
        val displayBuilder = StringBuilder(2048)
        var lastEmitTime = 0L
        var tokenCount = 0L
        // Same stall detection as [generate]: no first token within
        // [stallTimeoutMs] ⇒ cancel + report; hard ceiling (scaled to the
        // requested token budget) as backstop.
        val promptLength = messages.sumOf { it.content.length }
        val firstTokenTimeoutMs = stallTimeoutMs(promptLength)
        val hardTimeoutMs = hardGenerationTimeoutMs(config.maxTokens)
        val firstTokenSeen = CompletableDeferred<Unit>()
        val stallDetected = AtomicBoolean(false)
        try {
            withTimeout(hardTimeoutMs) {
                coroutineScope {
                    // Watchdog on the repository scope — see [generate]: the
                    // native decode blocks the collection thread, so the
                    // watchdog must always have a free thread to fire on.
                    val watchdog = scope.launch {
                        try {
                            withTimeout(firstTokenTimeoutMs) { firstTokenSeen.await() }
                        } catch (e: TimeoutCancellationException) {
                            stallDetected.set(true)
                            android.util.Log.e(TAG, "STALL: no token within ${firstTokenTimeoutMs}ms — cancelling chat generation")
                            engine.cancel()
                        }
                    }
                    try {
                        engine.generateChatStream(messages, addAssistant, config)
                            .onEach { result ->
                                when (result) {
                                    is Result.Success -> {
                                        val chunk = result.data
                                        if (chunk.delta.isNotEmpty() && !chunk.finished) {
                                            if (tokenCount == 0L) firstTokenSeen.complete(Unit)
                                            if (chunk.generatedTokens > 0) tokenCount = chunk.generatedTokens else tokenCount++
                                            // Thinking deltas stream for live progress but never enter
                                            // the final assistant message (answer text only).
                                            displayBuilder.append(chunk.delta)
                                            if (!chunk.isThinking) fullTextBuilder.append(chunk.delta)
                                            val now = System.currentTimeMillis()
                                            if (now - lastEmitTime >= 16L) {
                                                lastEmitTime = now
                                                _generationState.value = GenerationState.Generating(
                                                    prompt = lastUser,
                                                    streamingText = OutputSanitizer.streamingReady(displayBuilder.toString()),
                                                    generatedTokens = tokenCount
                                                )
                                            }
                                        }
                                    }

                                    is Result.Error -> throw result.exception
                                }
                            }
                            .collect()
                    } finally {
                        watchdog.cancel()
                    }
                }
            }

            if (stallDetected.get()) {
                _generationState.value = GenerationState.Failed(
                    message = "No tokens were generated within ${firstTokenTimeoutMs / 1000}s — inference stalled. " +
                        "Try a smaller model or switch the backend.",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("Generation stalled: no first token")
            }
            if (cancelRequested.getAndSet(false)) {
                android.util.Log.w(TAG, "Chat generation cancelled by user after $tokenCount tokens")
                _generationState.value = GenerationState.Cancelled
                return Result.Success(Unit)
            }

            val stats = engine.stats.firstOrNull()
            if (stats.isCorruptedStop()) {
                // The native side rolled the turn back (the partial response is
                // NOT part of the conversation — the KV cache was restored to
                // the pre-turn state), so surface it as a failure instead of
                // persisting a corrupted partial response. The native engine
                // reports "corrupted" (not "decode_error") for NaN/INF logits,
                // invalid token ids, decode failures, degenerate repetition and
                // mid-stream backend errors.
                android.util.Log.e(TAG, "Chat generation corrupted: stopReason=${stats?.stopReason}")
                _generationState.value = GenerationState.Failed(
                    message = "Decode error — try again",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("Decode error")
            }
            // NO-PROGRESS TERMINAL GUARD — same rule as [generate]: the native
            // loop fails a run that decodes many iterations without output; if
            // its stats blob reaches us instead of the thrown error, surface
            // the no-progress failure rather than a blank completion.
            if (stats?.stopReason == "no_progress") {
                android.util.Log.e(TAG, "Chat generation no-progress: stopReason=${stats?.stopReason}")
                _generationState.value = GenerationState.Failed(
                    message = "No-progress generation loop detected: the engine decoded repeatedly but produced no output. " +
                        "Try a smaller model, disable the GPU, or re-download the model.",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("No-progress generation loop detected")
            }
            // ZERO-OUTPUT TERMINAL GUARD — same rule as [generate]: a chat run
            // that produced NO text is only a valid completion when the model
            // hit EOS/EOG immediately. Otherwise (recovery-ladder exhaustion →
            // "{}" stats → empty stop reason, silent decode failure, or a
            // zero-token "max_tokens") the turn MUST surface as a visible
            // failure. The previous behavior published Completed(""), which
            // made the assistant placeholder vanish with no error and let the
            // next prompt re-enter the same failing cycle — the "endless
            // loop" (Preparing → Generating → nothing) symptom.
            // NOTE: the native bridge reports end-of-generation as "eog"
            // (llama.cpp convention) — treated identically to "eos" here so an
            // immediate-EOG empty completion is legitimate, not a failure.
            if (fullTextBuilder.isEmpty() && stats?.stopReason != "eos" && stats?.stopReason != "eog") {
                android.util.Log.e(
                    TAG,
                    "Chat generation produced no tokens (stopReason=${stats?.stopReason ?: "?"}) — surfacing as failure"
                )
                _generationState.value = GenerationState.Failed(
                    message = "The model produced no tokens (stop reason: ${stats?.stopReason?.ifBlank { "unknown" } ?: "unknown"}). " +
                        "Inference failed on this backend — try a smaller model, disable the GPU, or re-download the model.",
                    partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
                )
                return Result.error("No tokens generated")
            }
            android.util.Log.i(
                TAG,
                "Chat generation finished: tokens=$tokenCount stopReason=${stats?.stopReason ?: "?"} tps=${stats?.tokensPerSecond ?: 0f} timeMs=${stats?.totalTimeMs ?: 0}"
            )
            _generationState.value = GenerationState.Completed(text = OutputSanitizer.sanitize(fullTextBuilder.toString()), stats = stats)
            Result.Success(Unit)
        } catch (e: TimeoutCancellationException) {
            android.util.Log.e(
                TAG,
                "Chat generation timed out after ${hardTimeoutMs / 1000}s (${fullTextBuilder.length} chars, $tokenCount tokens)"
            )
            _generationState.value = GenerationState.Failed(
                message = "Generation exceeded the ${hardTimeoutMs / 1000}s time limit and was stopped.",
                partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
            )
            Result.error("Generation timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Chat generation failed: ${e.message}", e)
            _generationState.value = GenerationState.Failed(
                message = e.message ?: "Generation failed",
                partialText = OutputSanitizer.sanitize(fullTextBuilder.toString())
            )
            Result.Error(e)
        }
    }

    /**
     * True when the native engine reported a corrupted run (NaN/INF logits,
     * invalid token id, decode failure, degenerate repetition, mid-stream
     * backend error). The native bridge uses "corrupted" as the canonical stop
     * reason; "decode_error" is accepted for older builds. A corrupted run must
     * NEVER be published as Completed — its partial text would be persisted as
     * an assistant message and poison the next prompt's context.
     */
    private fun EngineStats?.isCorruptedStop(): Boolean =
        this != null && (stopReason == "corrupted" || stopReason == "decode_error")

    override suspend fun generateQuiet(
        prompt: String,
        config: GenerationConfig,
        timeoutMs: Long
    ): Result<String> = try {
        generationMutex.withLock {
            if (!engine.isLoaded()) {
                Result.error("Model not loaded")
            } else {
                // Bounded with a REAL deadline. A bare withTimeout CANNOT
                // interrupt the blocking JNI nativeGenerate call: the
                // TimeoutCancellationException is only delivered once the
                // native loop returns on its own, so a hung decode would hold
                // the generation mutex for the whole budget (wedging every
                // later chat turn) and the caller's own withTimeoutOrNull
                // would be a paper tiger that fires only AFTER the native
                // call unwinds — exactly the device symptom: the planner's
                // 30s budget was ignored and the pass ran the full 300s.
                // The watchdog below runs on the repository's OWN
                // multi-threaded scope (never the caller's dispatcher, which
                // may be blocked in JNI) and actively aborts the native loop
                // via engine.cancel() at the deadline — the same pattern as
                // the first-token stall watchdog in [generate].
                val watchdogFired = AtomicBoolean(false)
                val text = withTimeout(timeoutMs) {
                    coroutineScope {
                        val watchdog = scope.launch {
                            try {
                                delay(timeoutMs)
                                watchdogFired.set(true)
                                android.util.Log.e(
                                    TAG,
                                    "Quiet generation watchdog: aborting native decode after ${timeoutMs}ms"
                                )
                                engine.cancel()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                // Normal completion — the watchdog was
                                // cancelled in the finally below before the
                                // deadline elapsed.
                            }
                        }
                        try {
                            engine.generate(prompt, config).getOrNull()
                        } finally {
                            watchdog.cancel()
                        }
                    }
                }
                if (watchdogFired.get()) {
                    // The deadline elapsed and the engine was aborted — the
                    // partial output of a timed-out run must never be treated
                    // as a completed generation (same rule as cancelled chat).
                    Result.error("Quiet generation timed out")
                } else if (text == null) {
                    Result.error("Generation failed")
                } else if (engine.stats.firstOrNull().isCorruptedStop()) {
                    // The memory pipeline must never index a corrupted extractor
                    // output — fail the run so the caller skips it.
                    Result.error("Decode error")
                } else if (text.isEmpty() && engine.stats.firstOrNull()?.stopReason != "eos" &&
                           engine.stats.firstOrNull()?.stopReason != "eog") {
                    // Same zero-output terminal guard as the streaming paths:
                    // an empty quiet run (memory extraction, tool planning) that
                    // did not end on EOS/EOG (the native bridge reports "eog") is
                    // a silent failure — fail it so the caller surfaces an error
                    // instead of treating nothing as a completed generation.
                    Result.error("No tokens generated")
                } else {
                    Result.Success(text)
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        android.util.Log.e(TAG, "Quiet generation timed out")
        Result.error("Quiet generation timed out")
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Background pipelines (memory extraction) are cancelled when a new
        // chat turn supersedes them — that must propagate as cancellation,
        // never be swallowed into Result.Error.
        throw e
    } catch (e: Exception) {
        Result.Error(e)
    }

    override suspend fun cancelGeneration(): Result<Unit> = io.androllm.core.common.runCatching {
        cancelRequested.set(true)
        engine.cancel()
        _generationState.value = GenerationState.Cancelled
    }

    override suspend fun resetChat(): Result<Unit> = io.androllm.core.common.runCatching {
        engine.resetChat()
    }

    companion object {
        private const val TAG = "DefaultEngineRepository"

        /**
         * Stall detection (user requirement): if no first token arrives within
         * this window the generation is cancelled and reported instead of
         * spinning forever (hung GPU fence / dead sampler / broken model).
         * This is the FLOOR — [stallTimeoutMs] scales it up for long prompts.
         */
        private const val FIRST_TOKEN_TIMEOUT_MS = 5_000L

        /**
         * Never wait longer than this for a first token, however long the prompt.
         * Raised from 60s to 240s: on a Vulkan device-lost CPU fallback a large
         * planner prompt (3000+ tokens) can legitimately take 1-2 minutes of
         * prefill before the first generated token — the old cap false-stalled
         * healthy-but-slow CPU runs. Real stalls are still escaped by the
         * per-run watchdog ([generateQuiet] watchdog + [hardGenerationTimeoutMs]).
         */
        private const val FIRST_TOKEN_TIMEOUT_MAX_MS = 240_000L

        /**
         * Prefill budget per estimated prompt token (~4 chars/token): prompt
         * encoding on CPU scales with the prompt, so a large system prompt +
         * history can legitimately take longer than the 5s floor on weak
         * devices. 50ms/token ≈ 20 tokens/s of prefill — the low end of a
         * 7B-class CPU prefill on a phone. A healthy decode always beats it;
         * a stuck one never does. (20ms/token assumed ~50 tok/s and could
         * false-stall large models on CPU.)
         */
        private const val MS_PER_PROMPT_TOKEN = 50L

        /**
         * Hard ceiling for the whole run — escapes a run that never ends. The
         * floor keeps the previous 300s behavior for typical configs; the
         * ceiling never gets stricter than it was before this change.
         */
        private const val HARD_FLOOR_MS = 300_000L

        /** Absolute maximum: no run is ever allowed past 30 minutes. */
        private const val HARD_TIMEOUT_MAX_MS = 1_800_000L

        /**
         * Per-requested-token budget on top of the floor: a legitimately long
         * generation (hundreds of tokens at CPU speeds) must not be killed by
         * a fixed ceiling; a run producing tokens forever (broken stop tokens)
         * must still be escaped.
         */
        private const val MS_PER_GENERATED_TOKEN = 2_000L

        /** Bound for the post-load coherence probe so a broken model fails fast. */
        private const val SELF_TEST_TIMEOUT_MS = 30_000L

        /**
         * First-token stall budget for a prompt of [promptLength] characters.
         * Floor: [FIRST_TOKEN_TIMEOUT_MS] (the user-required 5s — a healthy
         * engine always produces its first token within it on typical prompts).
         * Scaled: +[MS_PER_PROMPT_TOKEN] per estimated prompt token so a long
         * CPU prefill is never misreported as a stall. Capped at
         * [FIRST_TOKEN_TIMEOUT_MAX_MS].
         */
        fun stallTimeoutMs(promptLength: Int): Long {
            val estimatedTokens = promptLength.coerceAtLeast(0) / 4
            return (FIRST_TOKEN_TIMEOUT_MS + estimatedTokens * MS_PER_PROMPT_TOKEN)
                .coerceIn(FIRST_TOKEN_TIMEOUT_MS, FIRST_TOKEN_TIMEOUT_MAX_MS)
        }

        /**
         * Hard ceiling for a run requesting [maxTokens] tokens. Never below
         * [HARD_FLOOR_MS] (previous fixed behavior) and never above
         * [HARD_TIMEOUT_MAX_MS].
         */
        fun hardGenerationTimeoutMs(maxTokens: Int): Long {
            val budget = maxTokens.coerceAtLeast(0).toLong() * MS_PER_GENERATED_TOKEN + HARD_FLOOR_MS
            return budget.coerceIn(HARD_FLOOR_MS, HARD_TIMEOUT_MAX_MS)
        }
    }

    override suspend fun getDebugInfo(): Result<EngineDebugInfo?> = engine.getDebugInfo()

    override suspend fun benchmarkBackends(
        prompt: String
    ): Result<List<BackendBenchmarkResult>> = generationMutex.withLock {
        io.androllm.core.common.runCatching {
            val info = engine.getLoadedModel() ?: return Result.Success(emptyList())
            val caps = backendCapabilities.value
            val model = Model(
                id = info.id,
                name = info.generalName,
                filePath = info.filePath,
                quantization = info.quantization,
                architecture = info.architecture,
                family = info.family
            )
            // Chain: NPU → GPU → CPU, pruned by the probe + model flags — the
            // same order the engine uses for an AUTO load.
            val candidates = BackendSelector.orderedCandidates(BackendType.AUTO, caps, model)
                .map { it.type }
                .distinct()
            val original = caps.selectedBackend
            val results = mutableListOf<BackendBenchmarkResult>()
            for (type in candidates) {
                results += benchmarkSingleBackend(model, type, prompt)
            }
            // Restore the backend the user had selected before benchmarking.
            runCatching { engine.loadModel(model, ModelLoadConfig(backend = original)) }
            results
        }
    }

    /**
     * Loads [model] on [type] and runs [prompt] once, measuring throughput,
     * first-token latency, initialization time and peak RAM. Never throws — a
     * failed backend is reported as a non-succeeded result so the comparison
     * table still shows every backend it tried.
     */
    private suspend fun benchmarkSingleBackend(
        model: Model,
        type: BackendType,
        prompt: String
    ): BackendBenchmarkResult {
        val loadStartedAt = System.currentTimeMillis()
        val load = engine.loadModel(model, ModelLoadConfig(backend = type, runSelfTest = false))
        if (load is Result.Error) {
            android.util.Log.w(TAG, "Backend benchmark: ${type.name} load failed — ${load.exception.message}")
            return BackendBenchmarkResult(
                backend = type,
                backendLabel = type.name,
                succeeded = false,
                error = load.exception.message ?: "load failed"
            )
        }
        val initTimeMs = (System.currentTimeMillis() - loadStartedAt).coerceAtLeast(0L)
        val loadedInfo = engine.getLoadedModel()
        var tokenCount = 0L
        var firstTokenMs = 0L
        val startedAt = System.currentTimeMillis()
        var failed = false
        var failure: String? = null
        try {
            engine.tokenStream(prompt, GenerationConfig(maxTokens = 64))
                .collect { res ->
                    when (res) {
                        is Result.Success -> {
                            val chunk = res.data
                            if (chunk.delta.isNotEmpty() && !chunk.finished) {
                                if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startedAt
                                if (!chunk.isThinking) tokenCount++
                            }
                        }
                        is Result.Error -> throw res.exception
                    }
                }
        } catch (e: Throwable) {
            failed = true
            failure = e.message ?: "generation failed"
            android.util.Log.w(TAG, "Backend benchmark: ${type.name} generation failed — $failure")
        }
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val stats = engine.stats.firstOrNull()
        val tps = if (tokenCount > 0) tokenCount * 1000f / elapsedMs else 0f
        return BackendBenchmarkResult(
            backend = type,
            backendLabel = type.name,
            vendor = loadedInfo?.vendor.orEmpty(),
            accelerator = loadedInfo?.accelerator.orEmpty(),
            averageTokensPerSecond = tps,
            peakTokensPerSecond = tps,
            promptLatencyMs = stats?.promptTimeMs ?: 0L,
            firstTokenMs = firstTokenMs,
            generationTimeMs = elapsedMs,
            initTimeMs = initTimeMs,
            peakRamBytes = stats?.memoryPeakBytes ?: 0L,
            succeeded = !failed,
            error = failure.orEmpty()
        )
    }

    override fun release() {
        engine.release()
        cancelRequested.set(false)
        _engineState.value = EngineState.Unloaded
        _generationState.value = GenerationState.Idle
        _memoryStats.value = null
    }
}
