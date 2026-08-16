package io.androllm.engine.core

import android.content.Context
import android.os.Debug
import com.google.ai.edge.litertlm.Channel as LiteRtChannel
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig as LitertEngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.models.Model
import io.androllm.engine.api.EngineState
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.backend.BackendCapabilities
import io.androllm.engine.backend.BackendSelector
import io.androllm.engine.backend.HardwareBackendProbe
import io.androllm.engine.backend.InferenceBackend
import io.androllm.engine.compat.ChatTemplateRenderer
import io.androllm.engine.compat.ContainerMetadataReader
import io.androllm.engine.compat.ModelCompatibilityException
import io.androllm.engine.compat.ModelFamilyConfig
import io.androllm.engine.compat.ModelFamilyRegistry
import io.androllm.engine.compat.OutputDecoder
import io.androllm.engine.compat.StopSequenceTracker
import io.androllm.engine.compat.TokenizerFiles
import io.androllm.engine.diagnostics.RuntimeLogger
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM-backed implementation of [InferenceEngine] — the production local
 * LLM runtime for AndroLLM.
 *
 * Chat/generation runs on Google's official LiteRT-LM Kotlin API
 * (`com.google.ai.edge.litertlm:litertlm-android`): a stateful [Engine] holds
 * the `.litertlm` model and one [Conversation] per chat session keeps the
 * multi-turn context (chat template, KV cache) across messages. Streaming is
 * `conversation.sendMessageAsync(text)` returning a `Flow<Message>`; cancel is
 * `conversation.cancelProcess()`; accelerator selection is a proper backend
 * layer ([io.androllm.engine.backend.InferenceBackend]) probing the device at
 * startup and falling back silently through NPU → GPU → CPU at load time.
 *
 * Embeddings (memory search) run through the raw LiteRT CompiledModel API in
 * [io.androllm.engine.embedding.LiteRtEmbeddingEngine] — see that class.
 *
 * All blocking LiteRT calls run on [Dispatchers.Default]; the main thread is
 * never blocked.
 */
@Singleton
class LiteRtLmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : InferenceEngine {

    private val logger = RuntimeLogger("LiteRtLmEngine")

    private var _capabilities = EngineCapabilities(
        name = "LiteRT-LM",
        version = LITERTLM_VERSION,
        backend = BackendType.CPU,
        supportsGpuAcceleration = true,
        maxContextLength = DEFAULT_MAX_CONTEXT,
        supportedFormats = listOf("litertlm")
    )
    override val capabilities: EngineCapabilities get() = _capabilities

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _stats = MutableStateFlow<EngineStats?>(null)
    override val stats: StateFlow<EngineStats?> = _stats.asStateFlow()

    private val _backendCapabilities = MutableStateFlow<BackendCapabilities>(BackendCapabilities.UNKNOWN)
    override val backendCapabilities: StateFlow<BackendCapabilities> = _backendCapabilities.asStateFlow()

    /** The stateful LiteRT-LM engine (created per model load). */
    @Volatile
    private var engine: Engine? = null

    /** Active chat conversation (multi-turn state). Recreated on reset. */
    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    private var loadedModel: EngineModelInfo? = null

    @Volatile
    private var loadedFilePath: String? = null

    /**
     * The compatibility contract of the loaded model's family (official chat
     * template, special tokens, stop sequences, generation defaults). Set at
     * load time by [ModelFamilyRegistry] — the engine never guesses.
     */
    @Volatile
    private var familyConfig: ModelFamilyConfig? = null

    /**
     * Decoder enforcing the family's decode rules on every generated text
     * (stop sequences + special-token stripping). Recreated per model load.
     */
    @Volatile
    private var outputDecoder: OutputDecoder? = null

    /**
     * The effective stop sequences of the loaded model: the family's official
     * stop tokens merged with the container's declared stop tokens and the
     * catalog entry's model-specific `stopSequences`. Every streaming
     * generation feeds a [StopSequenceTracker] built from this list and
     * terminates the native decode the moment one completes — a model that
     * never emits its own EOS can no longer generate forever.
     */
    @Volatile
    private var effectiveStopSequences: List<String> = emptyList()

    @Volatile
    private var modelLoadedAtMs: Long = 0L

    private var promptCount = 0

    /**
     * The ACTIVE backend of the last successful load — an [InferenceBackend]
     * carrying the display metadata (vendor/accelerator/delegate) the status
     * UI and debug panel read. Re-selected per load by [BackendSelector].
     */
    @Volatile
    private var activeBackendInfo: InferenceBackend? = null

    /** Wall-clock time to build the native engine on the active backend (ms). */
    @Volatile
    private var activeBackendInitMs: Long = 0L

    /**
     * Native `<|tool_call|>` markers the model emitted in the last chat
     * generation, consumed by the chat layer via [takeLastNativeToolCalls]
     * (cleared on read). Populated by both the blocking and streaming paths.
     */
    @Volatile
    private var lastNativeToolCalls: List<NativeToolCall> = emptyList()

    /**
     * Background scope for non-blocking recovery work (e.g. reseeding a
     * conversation whose context window filled mid-stream). Never the main
     * thread and never the native callback thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * True while a generation is actually in flight on the conversation.
     * Used to serialize generations (one at a time) and to gate reset.
     */
    private val generationActive = AtomicBoolean(false)

    override suspend fun initialize(config: EngineConfig): Result<Unit> = io.androllm.core.common.runCatching {
        // LiteRT-LM loads its native library on first Engine construction; no
        // global initialization is required. The startup HARDWARE PROBE runs
        // here — once, cheaply, best-effort — and drives every later backend
        // decision (automatic selection + adaptive settings UI).
        val probe = HardwareBackendProbe.probe(context)
        _backendCapabilities.value = probe
        val best = BackendSelector.bestAvailable(probe)
        _capabilities = _capabilities.copy(
            version = LITERTLM_VERSION,
            supportsGpuAcceleration = true,
            supportsNpuAcceleration = probe.npuUsable,
            backend = best,
            backendCapabilities = probe.copy(selectedBackend = best)
        )
        logger.i(
            "LiteRT-LM engine ready (runtime $LITERTLM_VERSION): " +
                "SoC-NPU=${if (probe.npuAvailable) "present" else "absent"} " +
                "NPU-usable=${probe.npuUsable} vendor=${probe.npuVendor ?: "—"} " +
                "GPU=${probe.gpuName ?: "—"} NNAPI=${probe.nnApiAvailable} " +
                "best=${best.name}"
        )
    }

    override fun isLoaded(): Boolean = engine != null && loadedModel != null

    override fun getLoadedModel(): EngineModelInfo? = loadedModel

    override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> =
        io.androllm.core.common.runCatching {
            val path = model.filePath
            check(!path.isNullOrBlank()) { "Model file path is empty" }
            val file = File(path)
            check(file.exists()) { "Model file not found: $path" }
            check(file.length() > 0) { "Model file is empty: $path" }

            _engineState.value = EngineState.Loading("Initializing LiteRT-LM…")

            // Backend selection: an explicit request wins; otherwise AUTO
            // (NPU → GPU → CPU) or the legacy `gpuLayers == 0` CPU-forcing
            // convention. The engine ATTEMPTS each candidate in order and falls
            // through to the next on initialization failure — a backend that
            // cannot initialize on this device/driver must fail safely and
            // silently, never crash the app (local-LLM spec). Model
            // compatibility flags prune candidates before any attempt.
            val preference = config.backend
                ?: if (config.gpuLayers == 0) BackendType.CPU else BackendType.AUTO
            val candidates = BackendSelector.orderedCandidates(preference, _backendCapabilities.value, model)
            check(candidates.isNotEmpty()) {
                "No backend available for this model (all declared backends unusable)"
            }

            val cacheDir = runCatching { context.cacheDir.absolutePath }.getOrNull()
            // The directory that must hold the vendor NPU dispatch libraries
            // (`libLiteRtDispatch_*.so`); passed to Backend.NPU(nativeLibraryDir).
            val npuLibDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()

            val (selectedBackend, initMs) = withContext(Dispatchers.Default) {
                ThreadManager.withBackgroundInferencePriority {
                    createEngineWithFallback(path, cacheDir, candidates, config.threads, npuLibDir)
                }
            }

            // --- Model-family resolution (the compatibility contract) ---
            // The family drives the chat template, the special tokens, the
            // stop sequences and the decode rules for EVERYTHING this engine
            // produces. Resolution uses the container's LlmMetadata first and
            // falls back to template/stop-token signatures and finally the
            // model name; an unresolvable model fails the load with the exact
            // reason instead of guessing.
            val container = runCatching { ContainerMetadataReader.read(file) }
                .onFailure { e ->
                    logger.w("LiteRT-LM container metadata unreadable (${e.message}) — relying on name/type heuristics")
                }
                .getOrNull()
            val family = ModelFamilyRegistry.resolve(container?.metadata, model.name)
            val sidecars = TokenizerFiles.loadFrom(file)
            // Sidecar tokenizer files complement the tokenizer the container
            // embeds (which the native runtime reads). They are REQUIRED only
            // when the container carries no tokenizer of its own — a container
            // with an embedded tokenizer must not be rejected just because the
            // HF export was not copied next to it.
            val missing = if (container?.hasAnyTokenizer == true) {
                emptyList()
            } else {
                sidecars.missingRequired(family.family)
            }
            if (missing.isNotEmpty()) {
                throw ModelCompatibilityException(
                    "Model '${model.name}' is missing required tokenizer file(s) next to ${file.name}: " +
                        missing.joinToString(", ") + ". Export the tokenizer sidecar files (Hugging Face " +
                        "format) for the ${family.family.displayName} family and try again."
                )
            }
            familyConfig = family.config
            // Stop-sequence metadata merge: the family's official stop tokens,
            // the container's declared stop tokens (LlmMetadata.stop_tokens)
            // and the catalog entry's model-specific `stopSequences`. The
            // engine reads all three automatically — generation stops on the
            // FIRST of any of them, never streaming a stop token to the UI.
            val modelStops = (model.stopSequences + container?.metadata?.stopTokens.orEmpty())
                .filter { it.isNotBlank() }
                .distinct()
            effectiveStopSequences = (family.config.stopSequences + modelStops).distinct()
            outputDecoder = OutputDecoder(family.config, extraStopSequences = modelStops)
            logger.i(
                "LiteRT-LM family resolved: ${family.family.displayName} " +
                    "(source ${family.source.name.lowercase()}), sidecars=${sidecars.present.joinToString(",") { it }}"
            )

            activeBackendInfo = selectedBackend
            activeBackendInitMs = initMs
            // Report the ACTIVE backend (not the hardcoded CPU default) so the
            // UI/dev-screen shows NPU/GPU when a delegate is in use, and mirror
            // the selection back into the probe (engine status reads it).
            _capabilities = _capabilities.copy(
                backend = selectedBackend.type,
                backendCapabilities = _backendCapabilities.value.copy(
                    selectedBackend = selectedBackend.type
                )
            )
            loadedFilePath = path
            modelLoadedAtMs = System.currentTimeMillis()
            promptCount = 0
            // A fresh model may support KV-cache reuse even if the previous
            // one didn't (template round-trip drift is per-container).
            reuseBroken.set(false)
            conversation?.let { runCatching { it.close() } }
            conversation = null

            // The container's REAL max context is baked into the compiled
            // TFLite (prefill subgraph); LiteRT-LM does not expose it through
            // the public API. Probing it once at load (one failing send whose
            // error carries the exact limit) prevents every later prompt from
            // overflowing: the app trims to the reported context, and a wrong
            // 8192/4096 default made even "hi" fail with "token ids are too
            // long" because the tool-advertisement system prompt alone
            // exceeded the container's true 2048.
            val detectedMaxContext = withContext(Dispatchers.Default) {
                detectMaxContext(eng())
            }
            if (detectedMaxContext > 0) {
                logger.i("LiteRT-LM max context detected: $detectedMaxContext tokens (container limit)")
            }
            val requestedContext = config.contextLength.takeIf { it > 0 } ?: DEFAULT_MAX_CONTEXT
            val contextLength = if (detectedMaxContext > 0) {
                // Never report more than the container can hold.
                requestedContext.coerceAtMost(detectedMaxContext)
            } else {
                requestedContext
            }

            loadedModel = EngineModelInfo(
                id = model.id,
                filePath = path,
                contextLength = contextLength,
                vocabSize = 0,
                backend = selectedBackend.type,
                vendor = selectedBackend.vendor.orEmpty(),
                accelerator = selectedBackend.accelerator.orEmpty(),
                delegate = selectedBackend.delegate,
                backendInitMs = initMs,
                quantization = model.quantization,
                architecture = model.architecture.ifBlank { family.family.displayName },
                generalName = model.name,
                chatTemplate = family.config.chatTemplate,
                family = family.family.displayName,
                nativeToolMarkers = family.family.nativeToolMarkers,
                toolAdvertisementCapChars = family.family.toolAdvertisementCapChars,
                templateSource = if (container?.metadata?.hasEmbeddedTemplate == true) {
                    "container + official override"
                } else {
                    "official (family registry)"
                },
                templateReady = true,
                nThreads = config.threads
            )

            val memStats = fetchMemoryStats()
            _engineState.value = EngineState.Ready(
                model = loadedModel!!,
                memoryStats = memStats,
                promptCount = 0,
                loadedSinceMs = modelLoadedAtMs
            )
            logger.i(
                "LiteRT-LM model loaded: ${model.name} backend=${selectedBackend.displayName} " +
                    "delegate=${selectedBackend.delegate} init=${initMs}ms"
            )
            loadedModel!!
        }

    override suspend fun unloadModel(): Result<Unit> = io.androllm.core.common.runCatching {
        _engineState.value = EngineState.Unloading
        conversation?.let { runCatching { it.close() } }
        conversation = null
        engine?.let { runCatching { it.close() } }
        engine = null
        loadedModel = null
        loadedFilePath = null
        generationActive.set(false)
        _engineState.value = EngineState.Unloaded
    }

    override suspend fun resetChat(): Result<Unit> = io.androllm.core.common.runCatching {
        val eng = engine ?: return Result.Success(Unit)
        // Never reset the conversation mid-generation: closing a conversation
        // while its decode is running races native state. Wait (bounded) for
        // the in-flight generation to drain, then recreate the conversation.
        val deadline = System.nanoTime() + RESET_CHAT_WAIT_NS
        while (generationActive.get() && System.nanoTime() < deadline) {
            delay(RESET_CHAT_POLL_MS)
        }
        withContext(Dispatchers.Default) {
            conversation?.let { runCatching { it.close() } }
            conversation = createConversationWithFamilyFlags(eng, conversationConfigForSampler(GenerationConfig()))
        }
        consumedTurns = emptyList()
        conversationSystemPrompt = null
        promptCount = 0
    }

    override fun tokenStream(prompt: String, config: GenerationConfig): Flow<Result<StreamChunk>> =
        callbackFlow {
            val eng = engine ?: run {
                trySend(Result.Error(EngineException("Model not loaded")))
                close()
                return@callbackFlow
            }
            if (!acquireGenerationSlot()) {
                trySend(Result.Error(EngineException("Generation already in progress")))
                close()
                return@callbackFlow
            }
            var conv: Conversation
            try {
                // createConversation is a blocking native call — never run it
                // on the collector's thread (Main when driven from a
                // viewModelScope turn).
                conv = withContext(Dispatchers.Default) {
                    val c = conversation ?: createConversationWithFamilyFlags(eng, conversationConfigForSampler(config))
                    conversation = c
                    c
                }
            } catch (e: Throwable) {
                generationActive.set(false)
                trySend(Result.Error(EngineException("Failed to create conversation: ${e.message}", e)))
                close()
                return@callbackFlow
            }

            val startedAt = System.currentTimeMillis()
            val firstTokenSeenAt = AtomicLong(0L)
            var tokenCount = 0L

            // --- Stop-sequence enforcement ----------------------------------
            // The decoder cuts the OUTPUT at the first stop sequence, but the
            // NATIVE decode keeps running until LiteRT's own EOS or the token
            // cap — a model that never emits its own EOS generates forever.
            // The tracker detects a completed stop sequence in the raw
            // fragment stream (rolling window, split-across-fragments safe)
            // and we terminate the decode immediately. Emission holds back
            // `holdback` RAW chars so a stop split across fragments is never
            // streamed — its leading chars stay un-emitted until the next
            // fragment proves they aren't part of a stop.
            val tracker = StopSequenceTracker(effectiveStopSequences + config.stopSequences)
            val holdback = tracker.holdbackLength
            val stopDetected = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            val rawTextBuilder = StringBuilder()
            val emittedBuilder = StringBuilder()

            fun emitDelta(text: String) {
                val delta = text.removePrefix(emittedBuilder.toString())
                if (delta.isNotEmpty()) {
                    emittedBuilder.append(delta)
                    trySend(Result.Success(StreamChunk(delta, false, tokenCount, tokenCount)))
                }
            }

            fun finishCleanly(stopReason: String) {
                if (!completed.compareAndSet(false, true)) return
                val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                val firstMs = if (firstTokenSeenAt.get() > 0) firstTokenSeenAt.get() - startedAt else elapsedMs
                logger.i("generation done: ${tokenCount} chunks in ${elapsedMs}ms, stop=$stopReason, text='${stripControlTokens(emittedBuilder.toString()).take(120)}'")
                _stats.value = applyBackendFields(EngineStats(
                    promptTokens = 0,
                    generatedTokens = tokenCount,
                    totalTimeMs = elapsedMs,
                    tokensPerSecond = tokenCount * 1000f / elapsedMs,
                    firstTokenMs = firstMs,
                    stopReason = stopReason
                ))
                trySend(Result.Success(StreamChunk("", true, tokenCount, tokenCount)))
                close()
            }

            // The Flow-returning sendMessageAsync overload is compiled against
            // kotlinx-coroutines <=1.6 (its onDone calls SendChannel.close$default,
            // which 1.8+/1.9 do not define) and crashes the native callback
            // thread the moment generation completes. The CALLBACK overload is
            // a plain interface with no coroutines coupling — bridge it here.
            val callback = object : MessageCallback {
                override fun onMessage(partial: Message) {
                    if (stopDetected.get()) return
                    val text = partial.toString()
                    if (text.isEmpty()) return
                    rawTextBuilder.append(text)
                    if (firstTokenSeenAt.get() == 0L) {
                        firstTokenSeenAt.set(System.currentTimeMillis())
                        logger.i("first token after ${firstTokenSeenAt.get() - startedAt}ms: '${stripControlTokens(text).take(60)}'")
                    }
                    tokenCount++
                    if (tracker.feed(text) != null) {
                        // A stop sequence just completed (possibly split across
                        // fragments — the rolling window handled it). Cut the
                        // raw stream at the stop start: the holdback guarantees
                        // no partial stop has been emitted, so the delta below
                        // is exactly the remaining answer text.
                        stopDetected.set(true)
                        val cutRaw = rawTextBuilder.substring(0, tracker.stopStartIndex.toInt())
                        emitDelta(outputDecoder?.clean(cutRaw) ?: stripControlTokens(cutRaw))
                        // Terminate the native decode NOW and retire the wedged
                        // conversation (same cleanup as [cancel]). The finished
                        // chunk is emitted only after the unwind completes, so
                        // no new turn can start against the closing conversation.
                        scope.launch(Dispatchers.Default) {
                            runCatching { conv.cancelProcess() }
                            runCatching { conversation?.close() }
                            conversation = null
                            consumedTurns = emptyList()
                            finishCleanly("stop_sequence")
                        }
                        return
                    }
                    // Emit the clean text up to `holdback` raw chars before the
                    // stream end. Control tokens (im_start/think/bos/...) belong
                    // to the tokenizer only — the decoder strips them so the UI
                    // receives pure decoded text even if a misbehaving model
                    // samples one.
                    val emissionPoint = (rawTextBuilder.length - holdback).coerceAtLeast(0)
                    val emittedRaw = rawTextBuilder.substring(0, emissionPoint)
                    emitDelta(outputDecoder?.clean(emittedRaw) ?: stripControlTokens(emittedRaw))
                }

                override fun onDone() {
                    if (completed.get()) return
                    // Natural end (native EOS / token cap): flush the held-back
                    // tail, then complete.
                    emitDelta(outputDecoder?.clean(rawTextBuilder.toString()) ?: stripControlTokens(rawTextBuilder.toString()))
                    finishCleanly("eos")
                }

                override fun onError(error: Throwable) {
                    // cancelProcess() races onDone/onError — when WE terminated
                    // on a stop sequence, the resulting error is the expected
                    // unwind, not a failure.
                    if (stopDetected.get()) {
                        finishCleanly("stop_sequence")
                        return
                    }
                    trySend(Result.Error(EngineException(error.message ?: "Generation failed", error)))
                    close()
                }
            }

            try {
                conv.sendMessageAsync(prompt, callback, maxOutputToken = streamingMaxOutputTokens(config))
            } catch (e: Throwable) {
                trySend(Result.Error(EngineException(e.message ?: "Generation failed", e)))
                close()
                return@callbackFlow
            }
            try {
                awaitClose { }
            } catch (e: CancellationException) {
                runCatching { conv.cancelProcess() }
                // A cancelled conversation is wedged: close it so the next turn
                // starts from a fresh conversation instead of failing with
                // "CANCELLED: Task cancelled".
                runCatching { conversation?.close() }
                conversation = null
                consumedTurns = emptyList()
                generationActive.set(false)
                publishReadyAfterGeneration()
                throw e
            }
            generationActive.set(false)
            publishReadyAfterGeneration()
        }

    override suspend fun buildChatPrompt(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean
    ): Result<String> = io.androllm.core.common.runCatching {
        // LiteRT-LM renders the chat template internally (ConversationConfig),
        // so there is no separate prompt-rendering step. This adapter returns a
        // plain-text representation for callers that only need a string
        // (diagnostics, logging); generation never goes through it.
        buildString {
            for (m in messages) {
                append("${m.role}: ${m.content}\n")
            }
            if (addAssistant) append("assistant: ")
        }
    }

    override suspend fun generate(prompt: String, config: GenerationConfig): Result<String> =
        io.androllm.core.common.runCatching {
            val eng = engine ?: throw EngineException("Model not loaded")
            if (!acquireGenerationSlot()) throw EngineException("Generation already in progress")
            var conv: Conversation? = null
            try {
                // Plain generation NEVER touches the chat conversation: memory
                // extraction, summarization and tool planning run here and must
                // not pollute (or be polluted by) the chat multi-turn state.
                // A throwaway conversation keeps them isolated; it is closed
                // when the run finishes.
                //
                // CRITICAL: createConversation + sendMessage are BLOCKING
                // native calls. Callers (tool planner, memory pipeline) run on
                // the Main dispatcher via viewModelScope; without this
                // dispatch every prompt freezes the UI thread in JNI (ANR /
                // "hangs"). The repository's watchdog can only abort a hung
                // decode if this thread is free to notice the cancel.
                withContext(Dispatchers.Default) {
                    outputDecoder?.reset()
                    conv = createConversationWithFamilyFlags(eng, conversationConfigForSampler(config))
                    val startedAt = System.currentTimeMillis()
                    val rawResult = sendMessageWithRetry(conv, prompt, config)
                    val result = decoderFor(config)?.clean(rawResult) ?: rawResult
                    val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                    _stats.value = applyBackendFields(EngineStats(
                        generatedTokens = 0,
                        totalTimeMs = elapsedMs,
                        stopReason = "eos"
                    ))
                    result
                }
            } finally {
                conv?.let { runCatching { it.close() } }
                generationActive.set(false)
            }
        }

    override suspend fun generateChat(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        config: GenerationConfig
    ): Result<String> = io.androllm.core.common.runCatching {
        val eng = engine ?: throw EngineException("Model not loaded")
        if (!acquireGenerationSlot()) throw EngineException("Generation already in progress")
        try {
            // Same Main-thread safety as [generate]: ensureConversationForHistory
            // may create a native conversation and sendMessageWithContextTrim
            // performs the blocking send — both must run off the caller's
            // (possibly Main) dispatcher.
            withContext(Dispatchers.Default) {
                outputDecoder?.reset()
                val conv = ensureConversationForHistory(eng, messages, config)
                val last = messages.lastOrNull() ?: throw EngineException("Empty message history")
                val startedAt = System.currentTimeMillis()
                val rawResult = sendMessageWithContextTrim(
                    eng, messages, config, conv, last.content
                )
                // Native tool-call markers are parsed here and stripped from
                // the persisted transcript — the assistant message stored for
                // the next turn must never contain raw `<|tool_call|>` blocks.
                lastNativeToolCalls = NativeToolCallScanner.scan(rawResult)
                val stripped = NativeToolCallScanner.strip(rawResult)
                val cleanResult = decoderFor(config)?.clean(stripped) ?: stripped
                val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                _stats.value = applyBackendFields(EngineStats(
                    generatedTokens = 0,
                    totalTimeMs = elapsedMs,
                    stopReason = "eos"
                ))
                updateConsumedTranscript(messages, cleanResult)
                cleanResult
            }
        } finally {
            generationActive.set(false)
        }
    }

    override fun generateChatStream(
        messages: List<ChatPromptMessage>,
        addAssistant: Boolean,
        config: GenerationConfig
    ): Flow<Result<StreamChunk>> = callbackFlow {
        val eng = engine ?: run {
            trySend(Result.Error(EngineException("Model not loaded")))
            close()
            return@callbackFlow
        }
        if (!acquireGenerationSlot()) {
            trySend(Result.Error(EngineException("Generation already in progress")))
            close()
            return@callbackFlow
        }
        val last = messages.lastOrNull()
        if (last == null) {
            generationActive.set(false)
            trySend(Result.Error(EngineException("Empty message history")))
            close()
            return@callbackFlow
        }

        var conv: Conversation
        try {
            // ensureConversationForHistory can create a native conversation —
            // a blocking call that must never run on the collector's thread
            // (Main when driven from a viewModelScope turn).
            conv = withContext(Dispatchers.Default) {
                ensureConversationForHistory(eng, messages, config)
            }
        } catch (e: Throwable) {
            generationActive.set(false)
            trySend(Result.Error(EngineException("Failed to create conversation: ${e.message}", e)))
            close()
            return@callbackFlow
        }
        // Prompt-debug logging (diagnostic): family, template, tokenizer and a
        // rendered mirror of the exact prompt. LiteRT renders internally, so
        // this replays the official template via ChatTemplateRenderer for
        // inspection; the full dump is gated behind config.debugTokenLogging.
        runCatching {
            val info = loadedModel
            logger.i(
                "chat prompt: model=${info?.generalName} family=${info?.family} " +
                    "template=${info?.chatTemplate} src=${info?.templateSource} " +
                    "tokenizer=${info?.tokenizerModel} messages=${messages.size} " +
                    "addAssistant=$addAssistant nCtx=${info?.contextLength} " +
                    "maxTokens=${config.maxTokens} temp=${config.temperature} " +
                    "topK=${config.topK} topP=${config.topP} reuseKv=${config.reuseKvCache}"
            )
            if (config.debugTokenLogging) {
                val fc = familyConfig
                if (fc != null) {
                    val rendered = ChatTemplateRenderer().render(
                        template = fc.chatTemplate,
                        messages = messages.map { ChatTemplateRenderer.RenderMessage(it.role, it.content) },
                        bosToken = fc.specialTokens.bos,
                        eosToken = fc.specialTokens.eos,
                        addGenerationPrompt = addAssistant,
                        extraContext = mapOf("enable_thinking" to config.enableThinking)
                    )
                    val parts = (rendered.length + 2999) / 3000
                    rendered.chunked(3000).forEachIndexed { i, chunk ->
                        logger.i("rendered prompt part ${i + 1}/$parts (${rendered.length} chars):\n$chunk")
                    }
                }
            }
        }
        val startedAt = System.currentTimeMillis()
        val firstTokenSeenAt = AtomicLong(0L)
        val cleanTextBuilder = StringBuilder()
        // Raw (pre-strip) stream accumulation: the native tool-call markers
        // are stripped from the display text but must be parsed at completion
        // — scan this buffer, never the cleaned transcript.
        val rawTextBuilder = StringBuilder()
        var tokenCount = 0L
        outputDecoder?.reset()

        // --- Stop-sequence enforcement (same as [tokenStream]) --------------
        // The decoder cuts the output at the first stop sequence, but the
        // NATIVE decode keeps running until LiteRT's own EOS or the token
        // cap — a model that never emits its own EOS generates forever and
        // the UI sits on a finished answer with the spinner running. The
        // tracker detects the completed stop in the fragment stream and we
        // cancel the native decode immediately. Emission holds back
        // `holdback` RAW chars so a stop split across fragments is never
        // streamed.
        val tracker = StopSequenceTracker(effectiveStopSequences + config.stopSequences)
        val holdback = tracker.holdbackLength
        val stopDetected = AtomicBoolean(false)
        val completed = AtomicBoolean(false)

        // Same callback-bridge reasoning as [tokenStream]: the Flow overload
        // crashes on kotlinx-coroutines 1.8+/1.9 (SendChannel.close$default no
        // longer exists), so stream through the plain callback interface.
        //
        // The run is BOUNDED: a finite output cap plus a bounded thinking
        // budget guarantee termination — an unbounded run is the "generation
        // never completes" hang (Stop stuck active, tok/s frozen at 0).
        //
        // Thinking models (Qwen3/Gemma3) emit reasoning tokens in the
        // partial's `channels` map, which Message.toString() drops. Those
        // deltas are streamed as flagged thinking chunks so the UI shows live
        // progress instead of a blank "Generating" that looks dead; the
        // repository excludes them from the final assistant message.
        var sendAttempts = 0

        fun emitDelta(text: String) {
            val delta = text.removePrefix(cleanTextBuilder.toString())
            if (delta.isNotEmpty()) {
                cleanTextBuilder.append(delta)
                trySend(Result.Success(StreamChunk(delta, false, tokenCount, tokenCount)))
            }
        }

        fun finishCleanly(stopReason: String) {
            if (!completed.compareAndSet(false, true)) return
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val firstMs = if (firstTokenSeenAt.get() > 0) firstTokenSeenAt.get() - startedAt else elapsedMs
            val fullClean = cleanTextBuilder.toString()
            // Native tool-call markers: parsed from the RAW buffer (the
            // cleaned text has them stripped) and handed to the chat layer.
            val scanRaw = if (stopReason == "stop_sequence") {
                rawTextBuilder.substring(0, tracker.stopStartIndex.toInt())
            } else {
                rawTextBuilder.toString()
            }
            lastNativeToolCalls = NativeToolCallScanner.scan(scanRaw)
            logger.i("chat generation done: ${tokenCount} chunks in ${elapsedMs}ms, stop=$stopReason, text='${stripControlTokens(fullClean).take(120)}' nativeCalls=${lastNativeToolCalls.size}")
            _stats.value = applyBackendFields(EngineStats(
                generatedTokens = tokenCount,
                totalTimeMs = elapsedMs,
                tokensPerSecond = tokenCount * 1000f / elapsedMs,
                firstTokenMs = firstMs,
                stopReason = stopReason
            ))
            trySend(Result.Success(StreamChunk("", true, tokenCount, tokenCount)))
            // The persisted transcript must be the FILTERED text (what the
            // next turn seeds its conversation with) — never raw markers.
            val persisted = outputDecoder?.clean(stripControlTokens(fullClean)) ?: stripControlTokens(fullClean)
            updateConsumedTranscript(messages, persisted)
            close()
        }

        val callback = object : MessageCallback {
            override fun onMessage(partial: Message) {
                if (stopDetected.get()) return
                // LiteRT-LM streams per-token FRAGMENTS (not cumulative text) —
                // see [tokenStream]. Emit each fragment directly.
                val thinking = partial.channels.values.joinToString("")
                if (thinking.isNotEmpty()) {
                    tokenCount++
                    rawTextBuilder.append(thinking)
                    tracker.feed(thinking)
                    if (firstTokenSeenAt.get() == 0L) firstTokenSeenAt.set(System.currentTimeMillis())
                    val cleanThinking = outputDecoder?.clean(stripControlTokens(thinking)) ?: stripControlTokens(thinking)
                    trySend(Result.Success(StreamChunk(cleanThinking, false, tokenCount, tokenCount, isThinking = true)))
                }
                val clean = partial.toString()
                if (clean.isNotEmpty()) {
                    rawTextBuilder.append(clean)
                    if (firstTokenSeenAt.get() == 0L) {
                        firstTokenSeenAt.set(System.currentTimeMillis())
                        logger.i("first chat token after ${firstTokenSeenAt.get() - startedAt}ms: '${stripControlTokens(clean).take(60)}'")
                    }
                    tokenCount++
                    if (tracker.feed(clean) != null) {
                        // A stop sequence just completed (possibly split across
                        // fragments — the rolling window handled it). Cut the
                        // raw stream at the stop start and emit the remaining
                        // answer delta; the holdback guarantees no partial stop
                        // was streamed. Then terminate the native decode and
                        // retire the wedged conversation (same cleanup as
                        // [cancel]). The finished chunk is emitted only after
                        // the unwind completes, so no new turn can start
                        // against the closing conversation.
                        stopDetected.set(true)
                        val cutRaw = rawTextBuilder.substring(0, tracker.stopStartIndex.toInt())
                        val finalClean = outputDecoder
                            ?.clean(NativeToolCallScanner.strip(cutRaw))
                            ?: NativeToolCallScanner.strip(cutRaw)
                        emitDelta(finalClean)
                        scope.launch(Dispatchers.Default) {
                            runCatching { conv.cancelProcess() }
                            runCatching { conversation?.close() }
                            conversation = null
                            consumedTurns = emptyList()
                            finishCleanly("stop_sequence")
                        }
                        return
                    }
                    // The emitted delta is the difference between the FULLY
                    // cleaned accumulated raw text (family special tokens,
                    // stop sequences, AND tool-call payloads removed) and what
                    // we already emitted. Per-token fragments split a marker
                    // block across tokens, so a per-fragment strip could leak
                    // "call: get_battery{}" into the UI — the accumulated
                    // diff never does. The strip is a linear scan over the
                    // bounded output text (at most a few thousand tokens).
                    val emissionPoint = (rawTextBuilder.length - holdback).coerceAtLeast(0)
                    val emittedRaw = rawTextBuilder.substring(0, emissionPoint)
                    val fullyCleaned = outputDecoder
                        ?.clean(NativeToolCallScanner.strip(emittedRaw))
                        ?: NativeToolCallScanner.strip(emittedRaw)
                    emitDelta(fullyCleaned)
                }
            }

            override fun onDone() {
                if (completed.get()) return
                // Natural end (native EOS / token cap): flush the held-back
                // tail, then complete.
                val flushRaw = outputDecoder
                    ?.clean(NativeToolCallScanner.strip(rawTextBuilder.toString()))
                    ?: NativeToolCallScanner.strip(rawTextBuilder.toString())
                emitDelta(flushRaw)
                finishCleanly("eos")
            }

            override fun onError(error: Throwable) {
                // cancelProcess() races onDone/onError — when WE terminated on
                // a stop sequence, the resulting error is the expected unwind.
                if (stopDetected.get()) {
                    finishCleanly("stop_sequence")
                    return
                }
                if (sendAttempts < MAX_STREAM_SEND_ATTEMPTS - 1 && isReseedable(error)) {
                    // Two recoverable cases (both from a reused conversation):
                    //  1. context window filled mid-stream (long chat),
                    //  2. template round-trip drift — the model cannot
                    //     continue a conversation (position-dependent think
                    //     wrapping).
                    // In both cases reseed from trimmed history OFF the native
                    // callback thread and restart the send on the fresh
                    // conversation. Bounded: at most one retry. The latch
                    // stops later turns from repeating the same failed reuse.
                    sendAttempts++
                    if (isReuseMismatch(error)) {
                        reuseBroken.set(true)
                        android.util.Log.w("LiteRtLmEngine", "Chat stream KV reuse mismatch — reseeding and retrying (${error.message})")
                    } else {
                        android.util.Log.w("LiteRtLmEngine", "Chat stream context full — reseeding and retrying (${error.message})")
                    }
                    val self: MessageCallback = this
                    scope.launch {
                        try {
                            conv = reseedAfterOverflow(eng, messages, config)
                            conv.sendMessageAsync(
                                Message.user(last.content),
                                self,
                                maxOutputToken = streamingMaxOutputTokens(config),
                                thinkingConfig = thinkingConfig()
                            )
                        } catch (e: Throwable) {
                            trySend(Result.Error(EngineException(e.message ?: "Chat generation failed", e)))
                            close()
                            generationActive.set(false)
                            publishReadyAfterGeneration()
                        }
                    }
                } else {
                    trySend(Result.Error(EngineException(error.message ?: "Chat generation failed", error)))
                    close()
                }
            }
        }

        try {
            conv.sendMessageAsync(
                Message.user(last.content),
                callback,
                maxOutputToken = streamingMaxOutputTokens(config),
                thinkingConfig = thinkingConfig()
            )
        } catch (e: Throwable) {
            trySend(Result.Error(EngineException(e.message ?: "Chat generation failed", e)))
            close()
            return@callbackFlow
        }
        try {
            awaitClose { }
        } catch (e: CancellationException) {
            runCatching { conv.cancelProcess() }
            // Same wedged-conversation cleanup as tokenStream: a cancelled
            // conversation must not poison the next turn.
            runCatching { conversation?.close() }
            conversation = null
            consumedTurns = emptyList()
            generationActive.set(false)
            publishReadyAfterGeneration()
            throw e
        }
        generationActive.set(false)
        publishReadyAfterGeneration()
    }

    /**
     * Builds a [ConversationConfig] carrying the caller's sampling parameters
     * and the loaded family's channels (e.g. Qwen3's thinking channel).
     * LiteRT-LM applies sampling exclusively through
     * `ConversationConfig.samplerConfig`; without it the runtime falls back to
     * degenerate defaults that emit pad tokens. Every conversation creation
     * must go through here.
     */
    private fun conversationConfigForSampler(config: GenerationConfig): ConversationConfig {
        val fam = familyConfig
        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = config.topK,
                topP = config.topP.toDouble(),
                temperature = config.temperature.toDouble(),
                seed = if (config.seed < 0) 0 else config.seed.toInt()
            ),
            // Explicit family channels: for families with a thinking channel
            // the container's own channel definitions are replaced by ours so
            // the overwritten template and the channel markers always agree.
            channels = fam?.thinkingChannel?.let {
                listOf(LiteRtChannel(it.channelName, it.start, it.end))
            }
        )
    }

    /**
     * Applies the loaded family's experimental flags (the official chat
     * template override, KV-cache channel filtering) to the GLOBAL
     * [ExperimentalFlags] singleton, then creates a conversation.
     *
     * `ExperimentalFlags.overwritePromptTemplate` is read by
     * `nativeCreateConversation`, so the flag must be set immediately before
     * every conversation creation. This is the enforcement point of the
     * family contract: the runtime renders the family's official template —
     * never the container's — so the prompt shape always matches what the
     * model was trained on.
     */
    @OptIn(ExperimentalApi::class)
    private fun createConversationWithFamilyFlags(eng: Engine, config: ConversationConfig): Conversation {
        val fam = familyConfig
        if (fam == null) {
            ExperimentalFlags.overwritePromptTemplate = null
            ExperimentalFlags.filterChannelContentFromKvCache = false
        } else {
            ExperimentalFlags.overwritePromptTemplate = fam.chatTemplate
            ExperimentalFlags.filterChannelContentFromKvCache = fam.thinkingChannel != null
        }
        return eng.createConversation(config)
    }

    /**
     * Sends [prompt] to [conv], handling the LiteRT context-overflow error:
     * when the conversation's KV cache fills, LiteRT-LM throws
     * `INVALID_ARGUMENT: Input token ids are too long` instead of evicting
     * automatically. The engine reseeds the conversation WITHOUT the stale
     * history and retries once — so long multi-turn conversations keep working
     * instead of failing after the context window fills.
     */
    private suspend fun sendMessageWithRetry(
        conv: Conversation,
        prompt: String,
        config: GenerationConfig
    ): String = try {
        conv.sendMessage(
            Message.user(prompt),
            maxOutputToken = streamingMaxOutputTokens(config),
            thinkingConfig = thinkingConfig()
        ).toString()
    } catch (e: com.google.ai.edge.litertlm.LiteRtLmJniException) {
        if (isReseedable(e)) {
            if (isReuseMismatch(e)) {
                // Template round-trip drift — this model can't continue a
                // conversation. Stop attempting reuse so later turns reseed
                // directly instead of failing the same way every time.
                reuseBroken.set(true)
                android.util.Log.w("LiteRtLmEngine", "KV reuse mismatch — reseeding conversation (${e.message})")
            } else {
                android.util.Log.w("LiteRtLmEngine", "Context window full — reseeding conversation (${e.message})")
            }
            withContext(Dispatchers.Default) {
                conversation?.let { runCatching { it.close() } }
                conversation = createConversationWithFamilyFlags(eng(), conversationConfigForSampler(config))
            }
            consumedTurns = emptyList()
            conversationSystemPrompt = null
            val fresh = conversation ?: throw e
            fresh.sendMessage(
                Message.user(prompt),
                maxOutputToken = streamingMaxOutputTokens(config),
                thinkingConfig = thinkingConfig()
            ).toString()
        } else {
            throw e
        }
    }

    /**
     * Chat variant of [sendMessageWithRetry]: on context overflow, drops the
     * oldest turns (keeping the last [CONTEXT_TRIM_KEEP_TURNS] and the new
     * prompt) and reseeds. Returns the final response text.
     */
    private suspend fun sendMessageWithContextTrim(
        eng: Engine,
        messages: List<ChatPromptMessage>,
        config: GenerationConfig,
        conv: Conversation,
        userPrompt: String
    ): String = try {
        conv.sendMessage(
            Message.user(userPrompt),
            maxOutputToken = streamingMaxOutputTokens(config),
            thinkingConfig = thinkingConfig()
        ).toString()
    } catch (e: com.google.ai.edge.litertlm.LiteRtLmJniException) {
        if (isReseedable(e)) {
            if (isReuseMismatch(e)) {
                reuseBroken.set(true)
                android.util.Log.w("LiteRtLmEngine", "Chat KV reuse mismatch — reseeding from history (${e.message})")
            } else {
                android.util.Log.w("LiteRtLmEngine", "Chat context window full — trimming history and reseeding (${e.message})")
            }
            val fresh = reseedAfterOverflow(eng, messages, config)
            fresh.sendMessage(
                Message.user(userPrompt),
                maxOutputToken = streamingMaxOutputTokens(config),
                thinkingConfig = thinkingConfig()
            ).toString()
        } else {
            throw e
        }
    }

    /**
     * Context-window-full recovery shared by the blocking and streaming chat
     * paths: drops the oldest turns (keeping the last [CONTEXT_TRIM_KEEP_TURNS]
     * plus the new prompt), reseeds a fresh conversation from the sanitized
     * remainder, and returns it. [sendMessageWithRetry] does the same without
     * history (plain generation).
     */
    private suspend fun reseedAfterOverflow(
        eng: Engine,
        messages: List<ChatPromptMessage>,
        config: GenerationConfig
    ): Conversation {
        val turns = messages.filter { it.role != "system" }
        val trimmed = turns.takeLast(CONTEXT_TRIM_KEEP_TURNS)
        // Sanitize to strict user/assistant alternation STARTING with a user
        // turn and ENDING with an assistant turn (the caller sends the next
        // user message; two consecutive user turns or a leading assistant turn
        // break LiteRT's chat template).
        val reseed = sanitizeSeed(trimmed.dropLast(1))
        val sampler = conversationConfigForSampler(config)
        val initial = reseed.map { m ->
            if (m.role == "assistant" || m.role == "model") Message.model(m.content)
            else Message.user(m.content)
        }
        val system = messages.firstOrNull { it.role == "system" }?.content
        val conversationConfig = if (system.isNullOrBlank()) {
            sampler.copy(initialMessages = initial)
        } else {
            sampler.copy(
                systemInstruction = com.google.ai.edge.litertlm.Contents.of(system),
                initialMessages = initial
            )
        }
        return withContext(Dispatchers.Default) {
            conversation?.let { runCatching { it.close() } }
            conversation = createConversationWithFamilyFlags(eng, conversationConfig)
            conversationSystemPrompt = system
            consumedTurns = reseed
            checkNotNull(conversation)
        }
    }

    /**
     * LiteRT-LM's chat template REQUIRES `initialMessages` to start with a
     * user turn and strictly alternate user/assistant. Trimming or history
     * repair can produce a seed that starts with an assistant turn or ends
     * with a user turn (two consecutive users after the caller appends the
     * next prompt). This normalizes a seed to that shape:
     *
     *  - drops leading assistant/model turns (a conversation must open with a
     *    user message),
     *  - drops same-role repeats,
     *  - ends on an assistant turn (the caller always sends the next user
     *    message itself).
     */
    private fun sanitizeSeed(turns: List<ChatPromptMessage>): List<ChatPromptMessage> {
        val out = ArrayList<ChatPromptMessage>(turns.size)
        var expectUser = true
        for (m in turns) {
            val isUser = m.role == "user"
            if (isUser != expectUser) continue
            out.add(m)
            expectUser = !expectUser
        }
        // Drop a trailing user turn so the seed ends on assistant — the caller
        // appends the next user message right after.
        if (out.isNotEmpty() && out.last().role == "user") {
            out.removeAt(out.size - 1)
        }
        return out
    }

    /**
     * Thinking is DISABLED at the conversation level.
     *
     * The catalog's Qwen3-0.6B container is a community repack that has NO
     * `thinking_end_token_ids`. With thinking enabled, the model emits literal
     * `<think>` markers as ordinary tokens (the runtime warns "Ignoring
     * thinking budget constraint") and the conversation stores them raw — the
     * re-render then double-wraps them and KV-cache reuse breaks. With
     * thinking off, the Qwen3 template emits an empty `<think>\n\n</think>`
     * generation prompt and the model answers directly: clean text, no
     * markers.
     *
     * KV-cache reuse for this container is structurally impossible either way
     * (the assistant branch is position-dependent: `<think>` wrapper when
     * last, plain content otherwise, so the native prefix-check re-render
     * never matches across turns). [ensureConversationForHistory] therefore
     * RESEEDS from sanitized history every turn — correct always, and the
     * prefix-mismatch retry in [sendMessageWithContextTrim]/
     * [generateChatStream] covers any model that does support reuse.
     */
    private fun thinkingConfig() = com.google.ai.edge.litertlm.ThinkingConfig(enableThinking = false)

    /**
     * Bounded streaming output cap. The caller's [GenerationConfig.maxTokens]
     * is respected up to [STREAMING_MAX_OUTPUT_TOKENS]; the 65536 "unlimited"
     * default is clamped so every streaming run terminates — a runaway decode
     * (broken stop tokens / thinking loop) must never hold the UI forever.
     */
    private fun streamingMaxOutputTokens(config: GenerationConfig): Int =
        config.maxTokens.takeIf { it > 0 && it < UNLIMITED_MAX_TOKENS_SENTINEL }
            ?.coerceAtMost(STREAMING_MAX_OUTPUT_TOKENS)
            ?: STREAMING_MAX_OUTPUT_TOKENS

    /**
     * Decoder for one generation, honoring the caller's per-call
     * [GenerationConfig.stopSequences] on top of the loaded model's effective
     * stop sequences. Returns the shared instance when the config adds
     * nothing (the common case).
     */
    private fun decoderFor(config: GenerationConfig): OutputDecoder? {
        val base = outputDecoder ?: return null
        if (config.stopSequences.isEmpty()) return base
        val fam = familyConfig ?: return base
        val extras = (effectiveStopSequences - fam.stopSequences) + config.stopSequences
        return OutputDecoder(fam, extraStopSequences = extras)
    }

    /** True when a LiteRT exception is the context-window-full error. */
    private fun isContextOverflow(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("token ids are too long") ||
            msg.contains("exceeding the maximum number of tokens") ||
            msg.contains("context window") ||
            msg.contains("prefill work group size exceeds available state entries")
    }

    /**
     * True when LiteRT rejected a KV-cache continuation because the
     * conversation's internal render no longer matches the re-render from
     * history (template round-trip drift — e.g. position-dependent `<think>`
     * wrapping). This is recoverable: reseed from sanitized history and
     * retry.
     */
    private fun isReuseMismatch(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.contains("does not start with the previous rendered template string") ||
            msg.contains("rendered template string")
    }

    /** True when a LiteRT send failure is recoverable via reseed. */
    private fun isReseedable(e: Throwable): Boolean =
        isContextOverflow(e) || isReuseMismatch(e)

    private fun eng(): Engine = checkNotNull(engine) { "Model not loaded" }

    /**
     * Builds the native LiteRT-LM engine, trying each [candidates] backend in
     * order and falling through SILENTLY on initialization failure (NPU → GPU →
     * CPU). The winner is the first backend whose `Engine.initialize()` does
     * not throw; if every candidate fails the last error is rethrown (the
     * repository surfaces it — inference must never silently disappear).
     */
    private fun createEngineWithFallback(
        path: String,
        cacheDir: String?,
        candidates: List<InferenceBackend>,
        threads: Int,
        npuLibDir: String?
    ): Pair<InferenceBackend, Long> {
        var lastError: Throwable? = null
        var attempted = 0
        for (candidate in candidates) {
            attempted++
            var newEngine: Engine? = null
            val startedAt = System.currentTimeMillis()
            try {
                newEngine = Engine(
                    LitertEngineConfig(
                        modelPath = path,
                        backend = candidate.toLiteRtBackend(threads, npuLibDir),
                        cacheDir = cacheDir
                    )
                )
                newEngine.initialize()
                engine?.let { old -> runCatching { old.close() } }
                engine = newEngine
                val initMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                if (attempted > 1) {
                    logger.w("LiteRT-LM backend fallback: running on ${candidate.displayName} after $attempted attempt(s)")
                }
                return candidate to initMs
            } catch (e: Throwable) {
                lastError = e
                runCatching { newEngine?.close() }
                logger.w(
                    "Backend ${candidate.displayName} (${candidate.delegate}) init failed " +
                        "(${e.message}) — ${if (candidates.size > attempted) "falling back" else "no more backends"}"
                )
            }
        }
        throw lastError ?: EngineException("No backend could be initialized")
    }

    /**
     * Merges the ACTIVE backend identity + runtime metrics into a fresh stats
     * record so the UI (status dashboard, generation panel, developer screen)
     * always reports which delegate produced the tokens.
     */
    private fun applyBackendFields(stats: EngineStats, peakTps: Float = 0f): EngineStats {
        val backend = activeBackendInfo
        return stats.copy(
            backend = backend?.type?.name?.lowercase() ?: "",
            delegate = backend?.delegate ?: "",
            vendor = backend?.vendor.orEmpty(),
            accelerator = backend?.accelerator.orEmpty(),
            initTimeMs = activeBackendInitMs,
            peakTokensPerSecond = maxOf(peakTps, stats.tokensPerSecond),
            currentRamBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        )
    }

    /**
     * Discovers the container's REAL max context (tokens) by sending an
     * oversized prompt and reading the limit from LiteRT's error message
     * ("Exceeding the maximum number of tokens allowed: N >= M" → M). The
     * container limit is baked into the compiled TFLite prefill subgraph and
     * is not exposed through the LiteRT-LM Kotlin API — without probing it,
     * the app trims to a guessed 4096/8192 and every prompt overflows.
     *
     * One failing send at load (the token-count check rejects the prompt
     * before any decode) is far cheaper than every later prompt failing at
     * runtime. Returns 0 when the limit cannot be determined (the probe
     * succeeded — a very large context — or errored without the limit).
     */
    private fun detectMaxContext(eng: Engine): Int {
        // ~20K chars ≈ 5000 tokens: larger than any catalog model's context.
        // A probe this large always trips the token-count check, which happens
        // before decode, so this is fast (no prefill work).
        val probePrompt = "The quick brown fox jumps over the lazy dog. ".repeat(800)
        val probeConfig = conversationConfigForSampler(GenerationConfig(maxTokens = 1))
        val conv = try {
            createConversationWithFamilyFlags(eng, probeConfig)
        } catch (e: Throwable) {
            logger.w("Max-context probe: conversation creation failed — ${e.message}")
            return 0
        }
        return try {
            conv.sendMessage(
                Message.user(probePrompt),
                maxOutputToken = 1
            )
            // Probe succeeded: the container accepts >= 5000 tokens.
            0
        } catch (e: com.google.ai.edge.litertlm.LiteRtLmJniException) {
            parseMaxContextLimit(e.message)
        } finally {
            runCatching { conv.close() }
        }
    }

    /** Extracts the "M" from "Exceeding the maximum number of tokens allowed: N >= M". */
    private fun parseMaxContextLimit(message: String?): Int {
        val msg = message ?: return 0
        val idx = msg.indexOf(">=")
        if (idx < 0) return 0
        val after = msg.substring(idx + 2).trim()
        return after.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    private fun ensureConversationForHistory(
        eng: Engine,
        messages: List<ChatPromptMessage>,
        config: GenerationConfig
    ): Conversation {
        val system = messages.firstOrNull { it.role == "system" }?.content
        val turns = messages.filter { it.role != "system" }
        // The incoming history EXCLUDING the new user message (the caller
        // sends that as the actual prompt).
        val history = turns.dropLast(1)
        val live = conversation
        val canReuse = !reuseBroken.get() && live != null &&
            conversationSystemPrompt == system && sanitizeSeed(history) == consumedTurns
        if (canReuse) {
            // KV-CACHE REUSE (the performance-critical path): the live
            // conversation already holds exactly this transcript (system + all
            // prior turns + the last assistant reply). The caller sends only
            // the NEW user message and LiteRT-LM continues decoding from the
            // resident KV cache — no re-prefill of the system prompt or prior
            // turns (which is what made every prompt take 10s+). Reseed only
            // when the history genuinely changed (first turn, edited/trimmed
            // history, regenerated reply, switched system prompt) or the model
            // proved reuse-broken (template round-trip drift — see
            // [reuseBroken]).
            return live
        }
        // Seed the full history MINUS the last user message, sanitized to
        // strict user/assistant alternation (leading assistant turns and
        // trailing user turns break LiteRT's chat template).
        val seed = sanitizeSeed(history)
        val initial = seed.map { m ->
            if (m.role == "assistant" || m.role == "model") Message.model(m.content)
            else Message.user(m.content)
        }
        val sampler = conversationConfigForSampler(config)
        val conversationConfig = if (system.isNullOrBlank()) {
            sampler.copy(initialMessages = initial)
        } else {
            sampler.copy(
                systemInstruction = com.google.ai.edge.litertlm.Contents.of(system),
                initialMessages = initial
            )
        }
        conversation?.let { runCatching { it.close() } }
        return createConversationWithFamilyFlags(eng, conversationConfig).also {
            conversation = it
            conversationSystemPrompt = system
            consumedTurns = seed
        }
    }

    @Volatile
    private var conversationSystemPrompt: String? = null

    /**
     * Set once a KV-cache continuation fails with a template round-trip
     * mismatch ("new rendered template string does not start with the
     * previous"). For such models every reuse attempt fails identically, so
     * [ensureConversationForHistory] stops attempting reuse and reseeds every
     * turn — correct always, and one wasted native call instead of one per
     * prompt. Cleared on [loadModel] (a different model may support reuse).
     */
    @Volatile
    private var reuseBroken: AtomicBoolean = AtomicBoolean(false)

    /**
     * The exact transcript (user + model turns, no system) that the CURRENT
     * conversation was seeded with. Bookkeeping for the context-trim reseed
     * path ([sendMessageWithContextTrim]); every turn re-seeds a fresh
     * conversation so this is informational, not a reuse decision input.
     */
    @Volatile
    private var consumedTurns: List<ChatPromptMessage> = emptyList()

    /**
     * Records the transcript the current conversation now holds (incoming
     * history PLUS the assistant reply it just produced), so a context-trim
     * reseed drops exactly the right old turns. [fullText] may be empty when
     * the reply was empty or the run failed; the transcript is still the
     * incoming turns (the reply is not persisted into the conversation in
     * that case).
     */
    private fun updateConsumedTranscript(
        messages: List<ChatPromptMessage>,
        fullText: String
    ) {
        val turns = messages.filter { it.role != "system" }
        consumedTurns = if (fullText.isNotEmpty()) {
            turns + ChatPromptMessage("assistant", fullText)
        } else {
            turns
        }
    }

    override fun takeLastNativeToolCalls(): List<NativeToolCall> {
        val calls = lastNativeToolCalls
        lastNativeToolCalls = emptyList()
        return calls
    }

    override fun cancel(): Result<Unit> = io.androllm.core.common.runCatching {
        if (conversation != null) {
            runCatching { conversation?.cancelProcess() }
            // A cancelled conversation is wedged — every later send fails with
            // "CANCELLED: Task cancelled" (the native onError fires instead of
            // a coroutine CancellationException, so the flow's own cleanup
            // never runs). Close it here so the next turn starts fresh.
            runCatching { conversation?.close() }
            conversation = null
            consumedTurns = emptyList()
        }
    }

    override fun benchmark(iterations: Int): Flow<Result<BenchmarkResult>> = flow {
        val eng = engine ?: run {
            emit(Result.Error(EngineException("Model not loaded")))
            return@flow
        }
        if (!acquireGenerationSlot()) {
            emit(Result.Error(EngineException("Generation already in progress")))
            return@flow
        }
        try {
            // createConversation + sendMessage are blocking native calls —
            // benchmark flows may be collected from any dispatcher, so run the
            // whole pass off the caller's thread.
            val conv = withContext(Dispatchers.Default) {
                conversation ?: createConversationWithFamilyFlags(
                    eng, conversationConfigForSampler(GenerationConfig())
                ).also { conversation = it }
            }
            val prompt = "The quick brown fox jumps over the lazy dog."
            var best = 0f
            var sum = 0f
            for (i in 0 until iterations) {
                val startedAt = System.currentTimeMillis()
                withContext(Dispatchers.Default) {
                    conv.sendMessage(Message.user(prompt), maxOutputToken = 32)
                }
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                // Approximate tps: 9 prompt tokens + 32 generated ~ decoded length
                val tps = 41f * 1000f / elapsed
                sum += tps
                if (tps > best) best = tps
            }
            emit(
                Result.Success(
                    BenchmarkResult(
                        iterations = iterations,
                        averageTokensPerSecond = sum / iterations,
                        bestTokensPerSecond = best,
                        averagePromptTokensPerSecond = 0f
                    )
                )
            )
        } catch (e: Throwable) {
            emit(Result.Error(EngineException(e.message ?: "Benchmark failed", e)))
        } finally {
            generationActive.set(false)
        }
    }

    override suspend fun getDebugInfo(): Result<EngineDebugInfo?> = io.androllm.core.common.runCatching {
        val model = loadedModel ?: return Result.Success(null)
        val fam = familyConfig
        val backend = activeBackendInfo
        val stats = _stats.value
        val isGpu = backend?.type == BackendType.GPU
        val isNpu = backend?.type == BackendType.NPU
        EngineDebugInfo(
            desc = "LiteRT-LM (${LITERTLM_VERSION})",
            generalName = model.generalName,
            architecture = model.architecture,
            family = model.family,
            backend = backend?.type?.name?.lowercase() ?: "cpu",
            gpuName = if (isGpu) (backend?.accelerator ?: "LiteRT GPU") else "",
            npuName = if (isNpu) (backend?.accelerator ?: "NPU") else "",
            npuVendor = if (isNpu) backend?.vendor.orEmpty() else "",
            npuAccelerator = if (isNpu) backend?.accelerator.orEmpty() else "",
            delegate = backend?.delegate ?: "",
            delegateVersion = LITERTLM_VERSION,
            backendInitMs = activeBackendInitMs,
            currentRamBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L),
            peakTokensPerSecond = stats?.peakTokensPerSecond ?: 0f,
            nCtx = model.contextLength,
            nThreads = model.nThreads,
            quantization = model.quantization,
            templateReady = true,
            templateSource = model.templateSource,
            bosToken = fam?.specialTokens?.bos ?: "",
            eosToken = fam?.specialTokens?.eos ?: "",
            modelSizeBytes = File(model.filePath).length(),
            promptTokens = 0,
            generatedTokens = stats?.generatedTokens ?: 0,
            firstTokenMs = stats?.firstTokenMs ?: 0,
            stopReason = stats?.stopReason ?: ""
        )
    }

    override fun release() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        loadedModel = null
        loadedFilePath = null
        familyConfig = null
        outputDecoder = null
        generationActive.set(false)
        _engineState.value = EngineState.Unloaded
        _stats.value = null
    }

    private fun fetchMemoryStats(): MemoryStats? {
        val model = loadedModel ?: return null
        val backend = activeBackendInfo
        val fileSize = runCatching { File(model.filePath).length() }.getOrDefault(0L)
        val type = backend?.type ?: BackendType.CPU
        return MemoryStats(
            modelSizeBytes = fileSize,
            contextSizeBytes = 0,
            peakMemoryBytes = fileSize,
            backend = type.name.lowercase(),
            backendReason = when (type) {
                BackendType.NPU -> "LiteRT NPU delegate active"
                BackendType.GPU -> "LiteRT GPU delegate active"
                else -> "CPU (XNNPACK)"
            },
            gpuName = if (type == BackendType.GPU) "LiteRT GPU" else "",
            gpuInferenceVerified = type == BackendType.GPU,
            loadedSinceMs = modelLoadedAtMs
        )
    }

    private fun publishReadyAfterGeneration() {
        val model = loadedModel ?: return
        promptCount++
        _engineState.value = EngineState.Ready(
            model = model,
            memoryStats = fetchMemoryStats(),
            promptCount = promptCount,
            loadedSinceMs = modelLoadedAtMs
        )
    }

    private suspend fun acquireGenerationSlot(): Boolean {
        if (generationActive.compareAndSet(false, true)) return true
        val deadline = System.nanoTime() + GENERATION_DRAIN_WAIT_NS
        while (generationActive.get() && System.nanoTime() < deadline) {
            delay(GENERATION_DRAIN_POLL_MS)
        }
        return generationActive.compareAndSet(false, true)
    }

    private class AtomicLong(initial: Long) {
        private val value = java.util.concurrent.atomic.AtomicLong(initial)
        fun get(): Long = value.get()
        fun set(v: Long) = value.set(v)
    }

    companion object {
        /**
         * The pinned LiteRT-LM Android runtime version (see
         * documentation/LOCAL_LLM_ARCHITECTURE.md).
         */
        const val LITERTLM_VERSION = "0.16.0"

        const val DEFAULT_MAX_CONTEXT = 8192

        private const val RESET_CHAT_WAIT_NS = 5_000_000_000L
        private const val RESET_CHAT_POLL_MS = 25L
        private const val GENERATION_DRAIN_WAIT_NS = 120_000_000_000L
        private const val GENERATION_DRAIN_POLL_MS = 25L

        /**
         * How many recent turns are kept when a conversation's context window
         * fills and the engine must reseed (oldest turns are dropped).
         */
        private const val CONTEXT_TRIM_KEEP_TURNS = 12

        /**
         * Chat-template / tokenizer control markers that must NEVER reach the
         * UI. LiteRT-LM renders the chat template internally from the
         * container's metadata; these markers belong to the tokenizer only.
         * A container whose template doesn't match its weights (or a
         * misbehaving model) can still sample them — [stripControlTokens]
         * removes them at the stream boundary so only decoded assistant text
         * is ever surfaced.
         */
        private val CONTROL_TOKEN_MARKERS = listOf(
            "<|im_start|>", "<|im_end|>", "<|endoftext|>", "<|end_of_text|>",
            "<|end_of_turn|>", "<|assistant|>", "<|user|>", "<|system|>",
            "<think>", "</think>", "<bos>", "<eos>", "<pad>", "<unk>",
            "<start_of_turn>", "<end_of_turn>", "<|start_header_id|>", "<|end_header_id|>",
            // Native tool-call markers (Gemma 4 / Gemma 3 repacks): stripped
            // from the display text; parsed separately via
            // [takeLastNativeToolCalls] so the UI never shows raw markers.
            // Both <|tool_call> (Gemma 4, no pipe) and <|tool_call|>
            // (Gemma 3, pipe) spellings are covered.
            "<|tool_call_start|>", "<|tool_call_end|>", "<|tool_call|>", "<tool_call|>", "<|tool_call>"
        )

        /**
         * Removes tokenizer control markers from a decoded text fragment. The
         * stream delta accumulation stays on RAW text (correct deltas); only
         * the emitted chunk is filtered.
         */
        fun stripControlTokens(text: String): String {
            var result = text
            for (marker in CONTROL_TOKEN_MARKERS) {
                if (marker in result) result = result.replace(marker, "")
            }
            return result
        }

        /** Max send attempts for a single chat stream (1 initial + 1 overflow reseed retry). */
        private const val MAX_STREAM_SEND_ATTEMPTS = 2

        /** Maximum output tokens for any single streaming run. */
        private const val STREAMING_MAX_OUTPUT_TOKENS = 8192

        /**
         * The GenerationConfig "unlimited" sentinel — anything at or above
         * this is treated as "no cap requested" and clamped to
         * [STREAMING_MAX_OUTPUT_TOKENS].
         */
        private const val UNLIMITED_MAX_TOKENS_SENTINEL = 65536
    }
}
