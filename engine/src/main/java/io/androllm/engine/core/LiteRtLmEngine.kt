package io.androllm.engine.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
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
import io.androllm.engine.compat.ModelFamily
import io.androllm.engine.compat.ModelFamilyConfig
import io.androllm.engine.compat.ModelFamilyRegistry
import io.androllm.engine.compat.OutputDecoder
import io.androllm.engine.compat.StopSequenceTracker
import io.androllm.engine.compat.TokenizerFiles
import io.androllm.engine.diagnostics.EngineCrashGuard
import io.androllm.engine.diagnostics.EnginePerformanceMonitor
import io.androllm.engine.diagnostics.RuntimeLogger
import io.androllm.engine.diagnostics.StartupProfiler
import io.androllm.engine.core.PrefixCache
import io.androllm.engine.core.BufferPool
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
import io.androllm.engine.utils.EngineErrorMapper
import io.androllm.engine.utils.LiteRtValidator
import io.androllm.engine.utils.ThreadManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.isActive
import kotlin.math.roundToLong

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

    private val _memoryStats = MutableStateFlow<MemoryStats?>(null)
    override val memoryStats: StateFlow<MemoryStats?> = _memoryStats.asStateFlow()

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

    /** Wall-clock time of the LAST successful full model load, including warm-up (ms). */
    @Volatile
    private var lastModelLoadMs: Long = 0L

    /**
     * Duration of the one-time post-load warm-up inference (ms); -1 when it
     * did not complete cleanly. Exposed through [getDebugInfo] so first-token
     * regressions are visible in diagnostics.
     */
    @Volatile
    private var lastWarmupMs: Long = -1L

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

    /** Polls Android's real process counters while LiteRT owns a model. */
    private var runtimeMetricsRefreshJob: Job? = null

    private var peakProcessPssBytes: Long = 0L
    private val decodeSpeedHistory = ArrayDeque<Float>()
    private var peakDecodeTokensPerSecond = 0f

    /**
     * True while a generation is actually in flight on the conversation.
     * Used to serialize generations (one at a time) and to gate reset.
     */
    private val generationActive = AtomicBoolean(false)

    /**
     * Last aggressive-fit diagnostics — produced during the most recent
     * [loadModel] attempt (success or refusal). Exposed through
     * [getDebugInfo] and [fetchMemoryStats] so diagnostics UI can show
     * what was reduced (context, batch, backend) before refusing.
     */
    @Volatile
    private var lastFitDiagnostics: io.androllm.engine.utils.MemoryFitDiagnostics? = null

    /** Effective context chosen by aggressive-fit (null = no reduction). */
    @Volatile
    private var fitEffectiveContext: Int? = null
    @Volatile
    private var fitEffectiveBackend: BackendType? = null

    override suspend fun initialize(config: EngineConfig): Result<Unit> = io.androllm.core.common.runCatching {
        // LiteRT-LM loads its native library on first Engine construction; no
        // global initialization is required. The startup HARDWARE PROBE runs
        // here — once, cheaply, best-effort — and drives every later backend
        // decision (automatic selection + adaptive settings UI).
        try {
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
        } catch (e: Throwable) {
            EngineCrashGuard.recordCrash("initialize", "", e)
            throw e
        }
    }

    /**
     * Runs the ONE-TIME post-load warm-up inference on an isolated throwaway
     * session: compiles the delegate's graphs, allocates compute buffers and
     * populates internal caches so the first USER prompt decodes immediately.
     *
     * Bounded by [WARMUP_TIMEOUT_MS] with an ACTIVE cancel — a hung compile
     * is aborted via `cancelProcess()` instead of wedging the load forever
     * (stalled-initialization requirement). Never throws; returns the warm-up
     * duration in ms, or -1 when it failed/was aborted (non-fatal).
     */
    private suspend fun runWarmup(eng: Engine): Long {
        if (!acquireGenerationSlot()) {
            logger.w("WARMUP skipped: generation slot unavailable")
            return -1L
        }
        var conv: Conversation? = null
        // The watchdog needs the session reference to actively abort a hung
        // compile (a bare coroutine timeout CANNOT interrupt blocking JNI).
        val convRef = java.util.concurrent.atomic.AtomicReference<Conversation?>(null)
        val startedAt = System.currentTimeMillis()
        // Watchdog on the engine scope: after WARMUP_TIMEOUT_MS without
        // completion, cancel the native compile so load always finishes.
        val watchdog = scope.launch {
            try {
                delay(WARMUP_TIMEOUT_MS)
                logger.w("WARMUP TIMEOUT after ${WARMUP_TIMEOUT_MS}ms — aborting the stuck initialization")
                convRef.get()?.let { runCatching { it.cancelProcess() } }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Normal completion — cancelled in the finally below.
            }
        }
        try {
            return withContext(Dispatchers.Default) {
                ThreadManager.withBackgroundInferencePriority {
                    EnginePerformanceMonitor.measure(EnginePerformanceMonitor.Stages.WARMUP) {
                        try {
                            conv = createConversationWithFamilyFlags(
                                eng, conversationConfigForSampler(GenerationConfig())
                            )
                            convRef.set(conv)
                            // Isolated throwaway session: never touches chat state,
                            // never appears in history. One token is enough to force
                            // full graph compilation + buffer allocation.
                            conv!!.sendMessage(Message.user("Hi"), maxOutputToken = 1)
                            (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                        } finally {
                            conv?.let { runCatching { it.close() } }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            logger.w("WARMUP failed or aborted (non-fatal): ${e.message}")
            return -1L
        } finally {
            watchdog.cancel()
            generationActive.set(false)
        }
    }

    override fun isLoaded(): Boolean = engine != null && loadedModel != null

    override fun getLoadedModel(): EngineModelInfo? = loadedModel

    override suspend fun loadModel(model: Model, config: ModelLoadConfig): Result<EngineModelInfo> =
        io.androllm.core.common.runCatching {
            val modelLoadStartedAt = System.currentTimeMillis()
            StartupProfiler.mark("modelLoadStart")
            StartupProfiler.logStage("ModelLoad requested", modelLoadStartedAt, "model=${model.name} size=${model.fileSize / (1024*1024)}MB")
            try {
                val path = model.filePath
                check(!path.isNullOrBlank()) { "Model file path is empty" }
                val file = File(path)
                check(file.exists()) { "Model file not found: $path" }
                check(file.length() > 0) { "Model file is empty: $path" }

                // ── Load-time memory hygiene: free transient caches before large-model mmap ──
                // Spec: free any unnecessary temporary memory before load, avoid keeping old sessions,
                // reuse buffers aggressively, free transient buffers as early as possible,
                // reuse buffers, pool temporary arrays, avoid duplicate copies, minimize native allocations.
                runCatching { BufferPool.trimForLowMemory() }
                runCatching { BufferPool.clear() }
                runCatching { PrefixCache.invalidateAll() }
                // Hint GC to release Java heap held by previous model metadata/decoder
                runCatching { System.gc() }
                if (engine != null || conversation != null) {
                    logger.i("PRE-LOAD CLEANUP: releasing stale session before new model")
                    runCatching { conversation?.close() }
                    conversation = null
                }
                // Evict stale container metadata cache entry for this path if present
                runCatching { ContainerMetadataReader.evictCache(file) }

                // --- Pre-load resource guard (aggressive-fit): try hard to fit large models like Qwen3 8B ---
                // Replaces the overly-conservative single-estimate refusal. Before rejecting, the guard:
                //   1) lower runtime context size  2) reduce batch/prefill memory  3) compact tokenizer/backend
                //   4) try most memory-efficient backend (NPU→GPU→CPU chain when AUTO)  5) recompute
                // Only when the smallest safe configuration (512-1024 ctx, aggressive scratch, CPU) still
                // cannot fit is the load refused — with detailed diagnostics naming every reduction attempted.
                val resourceGuard = io.androllm.engine.utils.ModelResourceGuard(context)
                val requestedCtxForGuard = config.contextLength.takeIf { it > 0 } ?: 4096
                val requestedBackendForGuard: BackendType = config.backend
                    ?: if (config.gpuLayers == 0) BackendType.CPU else BackendType.AUTO
                val footprintBackend = when (requestedBackendForGuard) {
                    BackendType.GPU -> BackendType.GPU
                    BackendType.NPU -> BackendType.NPU
                    BackendType.CPU -> BackendType.CPU
                    BackendType.AUTO -> BackendType.GPU
                    else -> BackendType.CPU
                }
                val availableBefore = resourceGuard.availableRamBytes()
                val lowMemBefore = resourceGuard.isSystemLowMemory()
                val fitResult = resourceGuard.attemptAggressiveFit(
                    fileSizeBytes = file.length(),
                    contextLength = requestedCtxForGuard,
                    availableBytes = availableBefore,
                    lowMemory = lowMemBefore,
                    requestedBackend = footprintBackend
                )
                // Surface detailed diagnostics for UI / logs (see MemoryFitDiagnostics)
                lastFitDiagnostics = when (fitResult) {
                    is io.androllm.engine.utils.AggressiveFitResult.Fit -> fitResult.diagnostics
                    is io.androllm.engine.utils.AggressiveFitResult.NoFit -> fitResult.diagnostics
                }
                // Remember the aggressive-fit effective values so later stages (container limit,
                // backend chain, warmup) use them instead of the original request.
                var effectiveContextFromFit: Int? = null
                var effectiveBatchFromFit: Int? = null
                var effectiveBackendFromFit: BackendType? = null
                var effectiveModeFromFit: io.androllm.engine.utils.MemoryMode? = null
                when (fitResult) {
                    is io.androllm.engine.utils.AggressiveFitResult.Fit -> {
                        effectiveContextFromFit = fitResult.effectiveContext
                        effectiveBackendFromFit = fitResult.effectiveBackend
                        effectiveBatchFromFit = fitResult.effectiveBatchSize
                        effectiveModeFromFit = fitResult.effectiveMode
                        fitEffectiveContext = fitResult.effectiveContext
                        fitEffectiveBackend = fitResult.effectiveBackend
                        if (fitResult.diagnostics.wasContextLowered || fitResult.diagnostics.wasBackendChanged) {
                            logger.i("AGGRESSIVE-FIT APPLIED: ${fitResult.diagnostics.toLogLine()} reductions=${fitResult.reductions}")
                            StartupProfiler.logStage(
                                "ResourceGuard:AggressiveFit",
                                modelLoadStartedAt,
                                "reduced ctx ${requestedCtxForGuard}→${fitResult.effectiveContext} backend ${requestedBackendForGuard}→${fitResult.effectiveBackend} mode=${fitResult.effectiveMode.label} need=${fitResult.diagnostics.estimatedTotalBytes / (1024*1024)}MB avail=${availableBefore / (1024*1024)}MB"
                            )
                        } else {
                            StartupProfiler.logStage(
                                "ResourceGuard",
                                modelLoadStartedAt,
                                "allowed ~${resourceGuard.estimateFootprint(file.length(), requestedCtxForGuard) / (1024*1024)} MB mode=${fitResult.effectiveMode.label}"
                            )
                        }
                    }
                    is io.androllm.engine.utils.AggressiveFitResult.NoFit -> {
                        logger.w("AGGRESSIVE-FIT REFUSAL after exhaustive reduction: ${fitResult.diagnostics.toLogLine()} attempted=${fitResult.attemptedConfigs.take(5)}")
                        throw ModelCompatibilityException(
                            fitResult.diagnostics.rejectionReason +
                                " (available ~${fitResult.diagnostics.totalAvailableBytes / (1024*1024)} MB, smallest need ~${fitResult.diagnostics.estimatedTotalBytes / (1024*1024)} MB)"
                        )
                    }
                }

                // --- Pre-load artifact validation (cheap, before any native work) ---
                // The LiteRT runtime rejects a wrong file only after minutes of
                // initialization ("INVALID_ARGUMENT: Unsupported file format"), and a
                // `.tflite` speech/embedding model can never be a chat model. The
                // container header, the chat-required format and the catalog size are
                // checked up front so the failure is instant and readable. The
                // checksum (full-file hash) is opt-in via [ModelLoadConfig.verifySha256]
                // — the download worker already verified it.
                val sizeValidation = LiteRtValidator.validateForLoad(
                    path = path,
                    expectedFormat = "litertlm",
                    expectedSizeBytes = model.fileSize.takeIf { it > 0 }
                )
                check(sizeValidation.isValid) { sizeValidation.errorMessage }

                if (config.verifySha256 && !model.sha256.isNullOrBlank()) {
                    val actualSha = LiteRtValidator.calculateSha256(path)
                    check(actualSha != null && actualSha.equals(model.sha256, ignoreCase = true)) {
                        "SHA-256 checksum mismatch — the model file is corrupted or was modified after download. " +
                            "Delete and re-download the model."
                    }
                }

                _engineState.value = EngineState.Loading("Initializing LiteRT-LM…")

            // Read LiteRT-LM's own container metadata before the Engine is
            // built. max_num_tokens is the model's authoritative context
            // contract; catalog/default settings must never overwrite it.
            val container = runCatching { EnginePerformanceMonitor.measure(EnginePerformanceMonitor.Stages.CONTAINER_READ) { ContainerMetadataReader.read(file) } }
                .onFailure { e ->
                    logger.w("LiteRT-LM container metadata unreadable (${e.message}) — relying on runtime probing")
                }
                .getOrNull()
            val metadataMaxContext = container?.metadata?.maxNumTokens?.takeIf { it > 0 }

            // Backend selection: an explicit request wins; otherwise AUTO
            // (NPU → GPU → CPU) or the legacy `gpuLayers == 0` CPU-forcing
            // convention. The engine ATTEMPTS each candidate in order and falls
            // through to the next on initialization failure — a backend that
            // cannot initialize on this device/driver must fail safely and
            // silently, never crash the app (local-LLM spec). Model
            // compatibility flags prune candidates before any attempt.
            // Aggressive-fit may have selected a more memory-efficient backend
            // (CPU) for AUTO requests under memory pressure — honor it to avoid
            // an OOM during GPU delegate init, while explicit requests remain strict.
            val basePreference = config.backend
                ?: if (config.gpuLayers == 0) BackendType.CPU else BackendType.AUTO
            val preference = if (effectiveBackendFromFit != null && basePreference == BackendType.AUTO) {
                // If the fit planner found CPU fits but GPU doesn't, prefer the fitting backend.
                // NPU is still tried first when it was the original best; GPU→CPU fallback remains.
                when (effectiveBackendFromFit) {
                    BackendType.CPU -> {
                        if (effectiveModeFromFit?.label == "aggressive-fit" || effectiveModeFromFit?.label == "low-memory") {
                            logger.i("BACKEND MEMORY ADJUST: AUTO→CPU for this load (aggressive-fit chose CPU for RAM)")
                            BackendType.CPU
                        } else basePreference
                    }
                    else -> basePreference
                }
            } else basePreference
            val candidates = BackendSelector.orderedCandidates(preference, _backendCapabilities.value, model)
            check(candidates.isNotEmpty()) {
                "No backend available for this model (all declared backends unusable)"
            }

            // Clamp container's maxNumTokens to the aggressive-fit effective context
            // so the engine never allocates a KV cache larger than the fitting budget.
            val clampedMaxNumTokens = when {
                metadataMaxContext != null && effectiveContextFromFit != null -> minOf(metadataMaxContext, effectiveContextFromFit)
                metadataMaxContext != null -> metadataMaxContext
                effectiveContextFromFit != null -> effectiveContextFromFit
                else -> null
            }
            if (clampedMaxNumTokens != null && clampedMaxNumTokens != metadataMaxContext) {
                logger.i("CONTEXT CLAMPED by aggressive-fit: container=${metadataMaxContext ?: "n/a"}→${clampedMaxNumTokens}")
            }

            val cacheDir = runCatching { context.cacheDir.absolutePath }.getOrNull()
            // The directory that must hold the vendor NPU dispatch libraries
            // (`libLiteRtDispatch_*.so`); passed to Backend.NPU(nativeLibraryDir).
            val npuLibDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()

            // Tune thread count for memory pressure: aggressive/low-memory modes reserve cores for OS
            val effectiveThreads = when (effectiveModeFromFit) {
                is io.androllm.engine.utils.MemoryMode -> {
                    when (effectiveModeFromFit.label) {
                        "low-memory" -> minOf(config.threads, 2)
                        "aggressive-fit" -> minOf(config.threads, 3)
                        else -> config.threads
                    }
                }
                else -> config.threads
            }
            if (effectiveThreads != config.threads) {
                logger.i("THREADS TUNED for memory: ${config.threads}→${effectiveThreads} (mode ${effectiveModeFromFit?.label})")
            }

            val (selectedBackend, initMs) = try {
                withContext(Dispatchers.Default) {
                    ThreadManager.withBackgroundInferencePriority {
                        EnginePerformanceMonitor.measure(EnginePerformanceMonitor.Stages.MODEL_INIT) {
                            createEngineWithFallback(
                                path = path,
                                cacheDir = cacheDir,
                                candidates = candidates,
                                threads = effectiveThreads,
                                npuLibDir = npuLibDir,
                                maxNumTokens = clampedMaxNumTokens
                            )
                        }
                    }
                }
            } catch (oom: OutOfMemoryError) {
                // Crash safety: clean partial allocations and surface a structured failure
                EngineCrashGuard.recordCrash("model_load_oom", candidates.firstOrNull()?.displayName ?: "", oom)
                logger.e("OOM during engine creation — releasing transient buffers and aborting load")
                runCatching { BufferPool.clear() }
                runCatching { System.gc() }
                throw ModelCompatibilityException(
                    "This model still exceeds safe memory limits after optimization. " +
                        "Reduced-context mode was attempted, but the model still needs more RAM. " +
                        "Try a lower quantization or a smaller context length. (OOM during allocation)"
                )
            } catch (e: Throwable) {
                // Detect native allocation failures early (e.g. mmap failed) and clean up
                if (e.message?.contains("mmap", ignoreCase = true) == true ||
                    e.message?.contains("allocation", ignoreCase = true) == true ||
                    e.message?.contains("out of memory", ignoreCase = true) == true) {
                    EngineCrashGuard.recordCrash("model_load_alloc", candidates.firstOrNull()?.displayName ?: "", e)
                    runCatching { BufferPool.clear() }
                }
                throw e
            }

            // --- Model-family resolution (the compatibility contract) ---
            // The family drives the chat template, the special tokens, the
            // stop sequences and the decode rules for EVERYTHING this engine
            // produces. Resolution uses the container's LlmMetadata first
            // (mapped through the shared ModelMetadataRegistry), then the
            // catalog family (registry-validated at indexing), then
            // template/stop-token signatures and finally the model name.
            // Containers whose identifier is supported by the runtime but has
            // no bespoke family configuration resolve to GENERIC, which uses
            // the container's own embedded template — a model can never fail
            // the load for lack of a family match.
            val family = ModelFamilyRegistry.resolve(container?.metadata, model.name, model.family)
            // Fast-path: when the container already embeds a tokenizer, sidecars are never required.
            // Avoid the 5x File.exists filesystem probes entirely (90%+ of LiteRT containers).
            // TokenizerFiles.loadFrom also has its own fast-path + LRU cache as defense-in-depth,
            // but skipping the call here saves even the canonicalPath/cache lookup.
            val sidecars = if (container?.hasAnyTokenizer == true) {
                logger.i("TokenizerFiles fast-path: container has embedded tokenizer — skipping sidecar reads")
                io.androllm.engine.compat.SidecarTokenizers()
            } else {
                TokenizerFiles.loadFrom(file, container)
            }
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
            // GENERIC mode uses the container's OWN embedded template as the
            // chat template (identity override — nothing is guessed); the
            // generic fallback in ChatTemplates only serves containers that
            // embed no template at all.
            familyConfig = if (family.family == ModelFamily.GENERIC) {
                val embedded = container?.metadata?.jinjaPromptTemplate
                if (embedded.isNullOrBlank()) family.config else family.config.copy(chatTemplate = embedded)
            } else {
                family.config
            }
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
            peakProcessPssBytes = 0L
            decodeSpeedHistory.clear()
            peakDecodeTokensPerSecond = 0f
            // A fresh model may support KV-cache reuse even if the previous
            // one didn't (template round-trip drift is per-container).
            reuseBroken.set(false)
            conversation?.let { runCatching { it.close() } }
            conversation = null

            // The container metadata is first-party LiteRT-LM data and wins
            // over every catalog or user-provided value, BUT it is still clamped
            // to the aggressive-fit budget so a 32768-train 8B model does not
            // allocate a 32k KV cache when only 2k fits. This is the "smallest
            // safe context" requirement.
            val requestedRaw = config.contextLength.takeIf { it > 0 } ?: DEFAULT_MAX_CONTEXT
            val clampedRequest = effectiveContextFromFit?.let { minOf(requestedRaw, it) } ?: requestedRaw
            if (effectiveContextFromFit != null && clampedRequest != requestedRaw) {
                logger.i("CONTEXT ADJUSTED by aggressive-fit: requested $requestedRaw → $clampedRequest")
            }
            val detectedMaxContext = if (metadataMaxContext == null) {
                withContext(Dispatchers.Default) { detectMaxContext(eng()) }
            } else {
                0
            }
            val contextLength = when {
                metadataMaxContext != null -> minOf(metadataMaxContext, clampedRequest)
                detectedMaxContext > 0 -> minOf(detectedMaxContext, clampedRequest)
                else -> clampedRequest
            }
            logger.i("LiteRT-LM max context: $contextLength tokens (${if (metadataMaxContext != null) "container metadata" else "runtime fallback"}${if (effectiveContextFromFit != null) " with aggressive-fit clamp $effectiveContextFromFit" else ""})")

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
                chatTemplate = familyConfig?.chatTemplate ?: family.config.chatTemplate,
                family = family.family.displayName,
                nativeToolMarkers = family.family.nativeToolMarkers,
                toolAdvertisementCapChars = family.family.toolAdvertisementCapChars,
                templateSource = when {
                    family.family == ModelFamily.GENERIC -> "container (generic mode)"
                    container?.metadata?.hasEmbeddedTemplate == true -> "container + official override"
                    else -> "official (family registry)"
                },
                templateReady = true,
                nThreads = effectiveThreads
            )
            _capabilities = _capabilities.copy(maxContextLength = contextLength)

            val memStats = fetchMemoryStats()
            _memoryStats.value = memStats

            // ── TTFT-CRITICAL: one-time warm-up, BEFORE Ready ────────────────
            // The first native invoke on a freshly built delegate pays graph
            // compilation + compute-buffer allocation (tens of seconds on
            // Vulkan). This MUST happen here — during load, behind the
            // WarmingUp state — and never race the user's first prompt. The
            // previous fire-and-forget version queued the first real request
            // BEHIND its own compilation at the JNI layer, where the first-
            // token watchdog killed it: "first prompt times out, identical
            // retry is instant". Bounded by [WARMUP_TIMEOUT_MS] with an
            // active cancel so a hung compile can never wedge the load.
            _engineState.value = EngineState.WarmingUp("Preparing ${selectedBackend.displayName}…")
            logger.i(
                "WARMUP START on ${selectedBackend.displayName}: compiling graphs / allocating " +
                    "delegate buffers once per load (bounded at ${WARMUP_TIMEOUT_MS / 1000}s)"
            )
            lastWarmupMs = runWarmup(checkNotNull(engine))
            if (lastWarmupMs > 0) {
                logger.i(
                    "WARMUP COMPLETE in ${lastWarmupMs}ms on ${selectedBackend.displayName} — " +
                        "delegate hot; the first user prompt will NOT pay this cost"
                )
            } else {
                logger.w(
                    "WARMUP did not complete cleanly (${lastWarmupMs}ms) — non-fatal; " +
                        "the first prompt may include one-time compile cost"
                )
            }

            startRuntimeMetricsRefresh()
            lastModelLoadMs = System.currentTimeMillis() - modelLoadStartedAt
            _engineState.value = EngineState.Ready(
                model = loadedModel!!,
                memoryStats = memStats,
                promptCount = 0,
                loadedSinceMs = modelLoadedAtMs
            )
            logger.i(
                "LiteRT-LM model loaded: ${model.name} backend=${selectedBackend.displayName} " +
                    "delegate=${selectedBackend.delegate} init=${initMs}ms " +
                    "warmup=${if (lastWarmupMs > 0) "${lastWarmupMs}ms" else "incomplete"} totalLoad=${lastModelLoadMs}ms"
            )
            // Release transient warmup buffers early — spec: free transient buffers as early as possible
            runCatching { System.gc() }
            loadedModel!!
            } catch (e: Throwable) {
                // Crash safety: catch OOM/allocation failures, clean partial allocations, reset state.
                if (e is OutOfMemoryError || e.message?.contains("out of memory", ignoreCase = true) == true) {
                    EngineCrashGuard.recordCrash("load_oom", "", e)
                    logger.e("LOAD OOM — releasing buffers and failing cleanly: ${e.message}")
                    runCatching { BufferPool.clear() }
                    runCatching { PrefixCache.invalidateAll() }
                    runCatching { System.gc() }
                } else if (e is kotlinx.coroutines.CancellationException) {
                    throw e
                }
                // Wrap non-Exception Throwables (OOMError) into an Exception for failModelLoad
                val ex = if (e is Exception) e else Exception(e.message ?: "Load failed: ${e.javaClass.simpleName}", e)
                failModelLoad(model, ex)
                throw ex
            }
        }

    /**
     * Runs on ANY [loadModel] failure: frees whatever native state the failed
     * load left behind (engine, conversation, metrics job) and publishes a
     * structured [EngineState.Failed] carrying the stage, an actionable
     * suggestion and whether a retry can succeed — so the UI never hangs on
     * "Initializing…" and never shows an opaque native error. Never throws:
     * failure handling must not mask the original load error.
     */
    private fun failModelLoad(model: Model, e: Exception) {
        try {
            val failure = EngineErrorMapper.map(e, model.name)
            conversation?.let { runCatching { it.close() } }
            conversation = null
            engine?.let {
                runCatching {
                    logger.i("DELEGATE DESTROY: closing interpreter after failed load")
                    it.close()
                }
            }
            engine = null
            loadedModel = null
            loadedFilePath = null
            familyConfig = null
            outputDecoder = null
            generationActive.set(false)
            stopRuntimeMetricsRefresh()
            // Aggressive cleanup for OOM safety: free pooled arrays, prefix cache, hint GC
            runCatching { BufferPool.clear() }
            runCatching { PrefixCache.invalidateAll() }
            runCatching { System.gc() }
            // Preserve lastFitDiagnostics for the Failed state's diagnostics (do NOT null it)
            // but clear live memory stats
            _memoryStats.value = null
            // If the failure was an OOM even after aggressive fit, enrich the message
            val enrichedMessage = if (e is OutOfMemoryError || e.message?.contains("OOM", ignoreCase = true) == true) {
                "This model still exceeds safe memory limits after optimization. Reduced-context mode was attempted, but the model still needs more RAM. Try a lower quantization or a smaller context length."
            } else failure.message
            // Attach fit diagnostics to the failure suggestion when available
            val enrichedSuggestion = failure.suggestion ?: lastFitDiagnostics?.let {
                if (it.isAggressiveFit) "Aggressive-fit tried: ${it.reductionsApplied.joinToString(", ")} → still insufficient. ${it.rejectionReason}" else null
            }
            _engineState.value = EngineState.Failed(
                message = enrichedMessage,
                stage = failure.stage,
                suggestion = enrichedSuggestion,
                retryable = failure.retryable
            )
            logger.w(
                "LiteRT-LM load failed (stage=${failure.stage}, retryable=${failure.retryable}): ${failure.message} fit=${lastFitDiagnostics?.toLogLine() ?: "n/a"}"
            )
        } catch (t: Throwable) {
            logger.w("Load-failure handling failed: ${t.message}")
        }
    }

    override suspend fun unloadModel(): Result<Unit> = io.androllm.core.common.runCatching {
        _engineState.value = EngineState.Unloading
        stopRuntimeMetricsRefresh()
        logger.i("DELEGATE DESTROY: unloadModel — closing session + interpreter (${activeBackendInfo?.displayName ?: "unknown delegate"})")
        conversation?.let { runCatching { it.close() } }
        conversation = null
        engine?.let { runCatching { it.close() } }
        engine = null
        // Invalidate caches and return pooled buffers on unload.
        PrefixCache.invalidateAll()
        BufferPool.clear()
        // Evict cached container metadata for the unloaded model to prevent
        // stale metadata from being reused if a different model is loaded.
        loadedFilePath?.let { path ->
            runCatching { ContainerMetadataReader.evictCache(java.io.File(path)) }
        }
        loadedModel = null
        loadedFilePath = null
        familyConfig = null
        outputDecoder = null
        generationActive.set(false)
        _memoryStats.value = null
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
            logger.i(
                "generation start: promptChars=${prompt.length} (~${prompt.length / 4} tokens) " +
                    "maxTokens=${streamingMaxOutputTokens(config)} temp=${config.temperature}"
            )
            logger.i("Generation Started")
            logger.i("Decoder Created: isolated session will be built")
            var conv: Conversation? = null
            try {
                // ISOLATED conversation: a plain (non-chat) generation must
                // never touch the resident CHAT conversation. Sharing it used
                // to inject raw extraction/benchmark prompts into the chat KV
                // cache — poisoning the next chat turn's context (the reuse
                // contract compares transcripts that no longer matched what
                // the conversation actually held). The throwaway conversation
                // is closed when the run ends; the multi-turn CHAT reuse
                // optimization in [ensureConversationForHistory] is untouched.
                conv = withContext(Dispatchers.Default) {
                    createConversationWithFamilyFlags(eng, conversationConfigForSampler(config))
                }
                logger.i("Decoder Created")
            } catch (e: Throwable) {
                generationActive.set(false)
                trySend(Result.Error(EngineException("Failed to create conversation: ${e.message}", e)))
                close()
                return@callbackFlow
            }
            val activeConv: Conversation = checkNotNull(conv)

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
            // Loop protection: same fragment forever / same phrase forever —
            // terminate cleanly instead of burning the whole token budget.
            val loopGuard = GenerationLoopGuard()
            // PERFORMANCE: use pooled buffers to avoid per-generation allocations.
            val pooledRaw = BufferPool.borrowBuilder(BufferPool.LARGE)
            val pooledEmitted = BufferPool.borrowBuilder(BufferPool.LARGE)
            val rawTextBuilder = pooledRaw.builder
            val emittedBuilder = pooledEmitted.builder

            /** Full cleanup shared by every exit path (idempotent). */
            val released = AtomicBoolean(false)
            fun releaseRun() {
                if (!released.compareAndSet(false, true)) return
                logger.i("CLEANUP STARTED: releasing generation flags, streaming buffers, session slot")
                logger.i("Cleanup Started")
                generationActive.set(false)
                publishReadyAfterGeneration()
                BufferPool.returnBuilder(pooledRaw)
                BufferPool.returnBuilder(pooledEmitted)
                logger.i("CLEANUP FINISHED — generation lock released, GENERATION JOB DESTROYED")
                logger.i("Cleanup Finished")
                logger.i("Generation Lock Released")
                logger.i("Generation Job Destroyed")
            }

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
                logger.i("EOS Received: stop=$stopReason")
                logger.i(
                    "generation done: ${tokenCount} chunks in ${elapsedMs}ms, stop=$stopReason, " +
                        "firstTokenMs=$firstMs, text='${stripControlTokens(emittedBuilder.toString()).take(120)}'"
                )
                _stats.value = buildGenerationStats(
                    conversation = activeConv,
                    totalTimeMs = elapsedMs,
                    fallbackFirstTokenMs = firstMs,
                    fallbackGeneratedTokens = tokenCount,
                    fallbackPromptTokens = prompt.length / 4L,
                    stopReason = stopReason
                )
                trySend(Result.Success(StreamChunk("", true, tokenCount, tokenCount)))
                close()
                logger.i("Streaming Finished")
            }

            /**
             * Terminates the native decode for [reason] and finishes. Runs on
             * the engine scope OFF the native callback thread (cancelProcess +
             * close are blocking calls); the finished chunk is emitted only
             * after the unwind completes so no new turn can start against the
             * closing conversation.
             */
            fun unwindAndFinish(reason: String) {
                scope.launch(Dispatchers.Default) {
                    runCatching { activeConv.cancelProcess() }
                    runCatching { activeConv.close() }
                    finishCleanly(reason)
                    releaseRun()
                }
            }

            // The Flow-returning sendMessageAsync overload is compiled against
            // kotlinx-coroutines <=1.6 (its onDone calls SendChannel.close$default,
            // which 1.8+/1.9 do not define) and crashes the native callback
            // thread the moment generation completes. The CALLBACK overload is
            // a plain interface with no coroutines coupling — bridge it here.
            val callback = object : MessageCallback {
                override fun onMessage(partial: Message) {
                    if (stopDetected.get()) return
                    // NEVER let an exception escape into the native callback
                    // thread: a throw here kills the runtime's callback pump
                    // and leaves the flow open forever ("Preparing answer…").
                    try {
                        // Extract ONLY the plain text content — Message.toString()
                        // serializes structured contents as JSON
                        // ([{"type":"text","text":"…"}]) which must never reach
                        // the UI. Tool responses/images/channels are dropped.
                        val text = MessageText.extract(partial)
                        if (text.isEmpty()) return
                        rawTextBuilder.append(text)
                        if (firstTokenSeenAt.get() == 0L) {
                            firstTokenSeenAt.set(System.currentTimeMillis())
                            val firstTokenMs = firstTokenSeenAt.get() - startedAt
                            EnginePerformanceMonitor.recordTiming(
                                EnginePerformanceMonitor.Stages.FIRST_TOKEN,
                                firstTokenMs * 1_000_000L,
                                mapOf("promptLength" to prompt.length.toString())
                            )
                            logger.i(
                                "PREFILL END / DECODER START — TTFT=${firstTokenMs}ms: '${stripControlTokens(text).take(60)}'"
                            )
                            logger.i("First Token")
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
                            logger.i("EOS/stop-sequence detected (${tracker.matched?.let { "match '${it.take(12)}'" } ?: "native"}) after $tokenCount fragments")
                            unwindAndFinish("stop_sequence")
                            return
                        }
                        if (loopGuard.feed(text)) {
                            // Pathological repetition: stop safely, keep the
                            // text produced so far and the conversation intact.
                            stopDetected.set(true)
                            logger.w("loop detected: ${loopGuard.detail} — terminating generation after $tokenCount fragments")
                            unwindAndFinish("loop_detected")
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
                    } catch (e: Throwable) {
                        // Surface as a stream error instead of crashing the
                        // native thread; onError-style cleanup keeps the engine
                        // usable for the next turn.
                        EngineCrashGuard.recordCrash("token_stream_callback", "", e)
                        logger.w("token-stream callback failed: ${e.message}")
                        if (completed.compareAndSet(false, true)) {
                            trySend(Result.Error(EngineException(e.message ?: "Generation failed", e)))
                            close()
                        }
                    }
                }

                override fun onDone() {
                    if (completed.get()) return
                    // Natural end (native EOS / token cap): flush the held-back
                    // tail, then complete.
                    try {
                        emitDelta(outputDecoder?.clean(rawTextBuilder.toString()) ?: stripControlTokens(rawTextBuilder.toString()))
                        finishCleanly("eos")
                    } catch (e: Throwable) {
                        EngineCrashGuard.recordCrash("token_stream_done", "", e)
                        logger.w("token-stream onDone flush failed: ${e.message}")
                        if (completed.compareAndSet(false, true)) {
                            trySend(Result.Error(EngineException(e.message ?: "Generation failed", e)))
                            close()
                        }
                    }
                }

                override fun onError(error: Throwable) {
                    // cancelProcess() races onDone/onError — when WE terminated
                    // on a stop sequence or loop guard, the resulting error is
                    // the expected unwind, not a failure.
                    if (stopDetected.get()) {
                        finishCleanly(if (loopGuard.isLooping) "loop_detected" else "stop_sequence")
                        return
                    }
                    // Record the crash for diagnostics (non-critical).
                    EngineCrashGuard.recordCrash("token_stream", "", error)
                    logger.w("token-stream error: ${error.message}")
                    if (completed.compareAndSet(false, true)) {
                        trySend(Result.Error(EngineException(error.message ?: "Generation failed", error)))
                        close()
                    }
                }
            }

            try {
                logger.i(
                    "PREFILL START: isolated session, promptChars=${prompt.length} (~${prompt.length / 4} tokens) — " +
                        "delegate=${activeBackendInfo?.displayName ?: "?"} already warm"
                )
                activeConv.sendMessageAsync(prompt, callback, maxOutputToken = streamingMaxOutputTokens(config))
            } catch (e: Throwable) {
                // The send never started: reset the slot + state HERE (the code
                // after awaitClose is skipped by this early return) so the next
                // turn cannot wedge on "generation already in progress".
                runCatching { activeConv.close() }
                if (completed.compareAndSet(false, true)) {
                    trySend(Result.Error(EngineException(e.message ?: "Generation failed", e)))
                }
                close()
                releaseRun()
                return@callbackFlow
            }
            try {
                awaitClose { }
            } catch (e: CancellationException) {
                runCatching { activeConv.cancelProcess() }
                runCatching { activeConv.close() }
                logger.i("generation cancelled by collector after $tokenCount fragments")
                finishCleanly("cancelled")
                throw e
            } finally {
                releaseRun()
            }
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
                    _stats.value = buildGenerationStats(
                        conversation = checkNotNull(conv),
                        totalTimeMs = elapsedMs,
                        fallbackFirstTokenMs = elapsedMs,
                        fallbackGeneratedTokens = 0L,
                        fallbackPromptTokens = prompt.length / 4L,
                        stopReason = "eos"
                    )
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
                _stats.value = buildGenerationStats(
                    conversation = conv,
                    totalTimeMs = elapsedMs,
                    fallbackFirstTokenMs = elapsedMs,
                    fallbackGeneratedTokens = 0L,
                    fallbackPromptTokens = messages.sumOf { it.content.length } / 4L,
                    stopReason = "eos"
                )
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
        // PERFORMANCE: check prefix cache to detect reusable prompt structure.
        // The system prompt and template prefix are the same for every turn in
        // a conversation — cache hits mean no unnecessary template re-rendering.
        runCatching {
            val info = loadedModel
            val systemPrompt = messages.firstOrNull { it.role == "system" }?.content ?: ""
            val templateHash = PrefixCache.hashPrompt(info?.chatTemplate ?: "")
            val systemHash = PrefixCache.hashPrompt(systemPrompt)
            val cached = PrefixCache.get(
                modelId = info?.id ?: "",
                templateHash = templateHash,
                systemPromptHash = systemHash,
                backend = info?.backend ?: BackendType.CPU,
                isChat = true
            )
            if (cached != null) {
                cached.hitCount++
                logger.d("prefix_cache hit: model=${info?.generalName} hits=${cached.hitCount}")
            } else {
                PrefixCache.put(
                    modelId = info?.id ?: "",
                    templateHash = templateHash,
                    systemPromptHash = systemHash,
                    backend = info?.backend ?: BackendType.CPU,
                    isChat = true,
                    prefixText = systemPrompt
                )
                logger.d("prefix_cache miss: storing prefix for model=${info?.generalName}")
            }
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
        logger.i(
            "chat decode start: messages=${messages.size} promptChars=${
                messages.sumOf { it.content.length }
            } (~${messages.sumOf { it.content.length } / 4} tokens) maxTokens=${streamingMaxOutputTokens(config)}"
        )
        logger.i("Generation Started")
        logger.i("Decoder Created: chat session ready")
        val firstTokenSeenAt = AtomicLong(0L)
        // PERFORMANCE: use pooled buffers to avoid per-generation allocations.
        val pooledClean = BufferPool.borrowBuilder(BufferPool.LARGE)
        val pooledRaw = BufferPool.borrowBuilder(BufferPool.LARGE)
        val cleanTextBuilder = pooledClean.builder
        val rawTextBuilder = pooledRaw.builder
        var tokenCount = 0L
        outputDecoder?.reset()
        logger.i("DECODER STATE RESET: decoder, sampler, stop tracker, loop guard and buffers are fresh for this run")

        /** Full cleanup shared by every exit path (idempotent). */
        val released = AtomicBoolean(false)
        fun releaseRun() {
            if (!released.compareAndSet(false, true)) return
            logger.i("CLEANUP STARTED: releasing generation flags, streaming buffers, session slot")
            logger.i("Cleanup Started")
            generationActive.set(false)
            publishReadyAfterGeneration()
            BufferPool.returnBuilder(pooledClean)
            BufferPool.returnBuilder(pooledRaw)
            logger.i("CLEANUP FINISHED — generation lock released, GENERATION JOB DESTROYED")
            logger.i("Cleanup Finished")
            logger.i("Generation Lock Released")
            logger.i("Generation Job Destroyed")
        }

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
        // Loop protection: terminate pathological repetition safely while
        // keeping everything produced so far (and the conversation history).
        val loopGuard = GenerationLoopGuard()

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

        /** Set by the FIRST callback of any kind (token/error/EOS). */
        val anyCallbackSeen = AtomicBoolean(false)

        fun emitDelta(text: String) {
            val delta = text.removePrefix(cleanTextBuilder.toString())
            if (delta.isNotEmpty()) {
                cleanTextBuilder.append(delta)
                trySend(Result.Success(StreamChunk(delta, false, tokenCount, tokenCount)))
            }
        }

        fun finishCleanly(stopReason: String, persistTranscript: Boolean = true) {
            if (!completed.compareAndSet(false, true)) return
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val firstMs = if (firstTokenSeenAt.get() > 0) firstTokenSeenAt.get() - startedAt else elapsedMs
            logger.i("EOS Received: stop=$stopReason")
            val fullClean = cleanTextBuilder.toString()
            // Native tool-call markers: parsed from the RAW buffer (the
            // cleaned text has them stripped) and handed to the chat layer.
            val scanRaw = if (stopReason == "stop_sequence") {
                rawTextBuilder.substring(0, tracker.stopStartIndex.toInt())
            } else {
                rawTextBuilder.toString()
            }
            lastNativeToolCalls = NativeToolCallScanner.scan(scanRaw)
            logger.i(
                "chat generation done: ${tokenCount} chunks in ${elapsedMs}ms, stop=$stopReason, " +
                    "firstTokenMs=$firstMs, text='${stripControlTokens(fullClean).take(120)}' nativeCalls=${lastNativeToolCalls.size}"
            )
            _stats.value = buildGenerationStats(
                conversation = conv,
                totalTimeMs = elapsedMs,
                fallbackFirstTokenMs = firstMs,
                fallbackGeneratedTokens = tokenCount,
                fallbackPromptTokens = messages.sumOf { it.content.length } / 4L,
                stopReason = stopReason
            )
            trySend(Result.Success(StreamChunk("", true, tokenCount, tokenCount)))
            logger.i("Streaming Finished")
            // The persisted transcript must be the FILTERED text (what the
            // next turn seeds its conversation with) — never raw markers.
            // A CANCELLED run's partial answer is never persisted: it must
            // not leak into the next turn's context.
            if (persistTranscript) {
                val persisted = outputDecoder?.clean(stripControlTokens(fullClean)) ?: stripControlTokens(fullClean)
                updateConsumedTranscript(messages, persisted)
            }
            close()
        }

        /**
         * Terminates the native decode for [reason] OFF the native callback
         * thread, then finishes the stream. The wedged conversation is
         * retired exactly like [cancel] does; the finished chunk is emitted
         * only after the unwind completes.
         */
        fun unwindAndFinish(reason: String) {
            scope.launch(Dispatchers.Default) {
                runCatching { conv.cancelProcess() }
                runCatching { conversation?.close() }
                conversation = null
                consumedTurns = emptyList()
                finishCleanly(reason)
            }
        }

        /**
         * BOUNDED recovery from a broken/wedged reused session: abort the
         * current decode, retire the conversation, reseed from history and
         * resend ONCE. Shared by the onError path (context overflow /
         * template round-trip drift) and the stalled-session watchdog below —
         * both are the same failure family: the resident conversation can no
         * longer continue this turn. [latchReuseBroken] permanently disables
         * reuse for models whose drift makes every reuse fail identically.
         */
        fun retryOnFreshSession(cb: MessageCallback, reason: String, latchReuseBroken: Boolean) {
            if (sendAttempts >= MAX_STREAM_SEND_ATTEMPTS - 1) {
                logger.w("chat-stream retry budget exhausted ($reason) — surfacing as failure")
                return
            }
            sendAttempts++
            if (latchReuseBroken) reuseBroken.set(true)
            android.util.Log.w("LiteRtLmEngine", "Chat stream recovering ($reason) — retiring session, reseeding, retrying once")
            scope.launch(Dispatchers.Default) {
                try {
                    runCatching { conv.cancelProcess() }
                    conv = reseedAfterOverflow(eng, messages, config)
                    logger.i("PREFILL START: fresh reseeded session after recovery ($reason)")
                    conv.sendMessageAsync(
                        Message.user(last.content),
                        cb,
                        maxOutputToken = streamingMaxOutputTokens(config),
                        thinkingConfig = thinkingConfig()
                    )
                } catch (e: Throwable) {
                    trySend(Result.Error(EngineException(e.message ?: "Chat generation failed", e)))
                    close()
                    releaseRun()
                }
            }
        }

        val callback = object : MessageCallback {
            override fun onMessage(partial: Message) {
                anyCallbackSeen.set(true)
                if (stopDetected.get()) return
                // NEVER let an exception escape into the native callback
                // thread — it would kill the runtime's callback pump and
                // leave this flow open forever (infinite "Preparing…").
                try {
                    // LiteRT-LM streams per-token FRAGMENTS (not cumulative text).
                    // Thinking deltas arrive via the channels map; answer text via
                    // the structured contents — extracted with [MessageText] so a
                    // serialized contents payload ([{"type":"text",…}]) can never
                    // leak into the chat.
                    val thinking = partial.channels.values.joinToString("")
                    if (thinking.isNotEmpty()) {
                        tokenCount++
                        rawTextBuilder.append(thinking)
                        tracker.feed(thinking)
                        if (firstTokenSeenAt.get() == 0L) firstTokenSeenAt.set(System.currentTimeMillis())
                        val cleanThinking = outputDecoder?.clean(stripControlTokens(thinking)) ?: stripControlTokens(thinking)
                        trySend(Result.Success(StreamChunk(cleanThinking, false, tokenCount, tokenCount, isThinking = true)))
                    }
                    val clean = MessageText.extract(partial)
                    if (clean.isNotEmpty()) {
                        rawTextBuilder.append(clean)
                        if (firstTokenSeenAt.get() == 0L) {
                            firstTokenSeenAt.set(System.currentTimeMillis())
                            logger.i(
                                "PREFILL END / DECODER START — TTFT=${firstTokenSeenAt.get() - startedAt}ms: '${stripControlTokens(clean).take(60)}'"
                            )
                            logger.i("First Token")
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
                            logger.i("EOS/stop-sequence detected (${tracker.matched?.let { "match '${it.take(12)}'" } ?: "native"}) after $tokenCount fragments")
                            unwindAndFinish("stop_sequence")
                            return
                        }
                        if (loopGuard.feed(clean)) {
                            // Pathological repetition: stop safely. Text produced
                            // so far is preserved and the turn still completes as
                            // a normal answer — the history stays intact.
                            stopDetected.set(true)
                            logger.w("chat loop detected: ${loopGuard.detail} — terminating generation after $tokenCount fragments")
                            unwindAndFinish("loop_detected")
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
                } catch (e: Throwable) {
                    EngineCrashGuard.recordCrash("chat_stream_callback", "", e)
                    logger.w("chat-stream callback failed: ${e.message}")
                    if (completed.compareAndSet(false, true)) {
                        trySend(Result.Error(EngineException(e.message ?: "Chat generation failed", e)))
                        close()
                    }
                }
            }

            override fun onDone() {
                anyCallbackSeen.set(true)
                if (completed.get()) return
                // Natural end (native EOS / token cap): flush the held-back
                // tail, then complete.
                try {
                    val flushRaw = outputDecoder
                        ?.clean(NativeToolCallScanner.strip(rawTextBuilder.toString()))
                        ?: NativeToolCallScanner.strip(rawTextBuilder.toString())
                    emitDelta(flushRaw)
                    finishCleanly("eos")
                } catch (e: Throwable) {
                    EngineCrashGuard.recordCrash("chat_stream_done", "", e)
                    logger.w("chat-stream onDone flush failed: ${e.message}")
                    if (completed.compareAndSet(false, true)) {
                        trySend(Result.Error(EngineException(e.message ?: "Chat generation failed", e)))
                        close()
                    }
                }
            }

            override fun onError(error: Throwable) {
                anyCallbackSeen.set(true)
                // cancelProcess() races onDone/onError — when WE terminated on
                // a stop sequence or the loop guard, the resulting error is
                // the expected unwind.
                if (stopDetected.get()) {
                    finishCleanly(if (loopGuard.isLooping) "loop_detected" else "stop_sequence")
                    return
                }
                if (sendAttempts < MAX_STREAM_SEND_ATTEMPTS - 1 && isReseedable(error)) {
                    // Two recoverable cases (both from a reused conversation):
                    //  1. context window filled mid-stream (long chat),
                    //  2. template round-trip drift — the model cannot
                    //     continue a conversation (position-dependent think
                    //     wrapping).
                    // Bounded: at most one retry via the shared recovery path.
                    retryOnFreshSession(this, error.message ?: "recoverable send failure", isReuseMismatch(error))
                } else {
                    logger.w("chat-stream error: ${error.message}")
                    trySend(Result.Error(EngineException(error.message ?: "Chat generation failed", error)))
                    close()
                }
            }
        }

        // ── STALLED-SESSION WATCHDOG (reused sessions only) ─────────────────
        // Turn N>1 reuses the resident conversation. A degenerate mode of
        // reused-session continuation is a SILENT native stall:
        // sendMessageAsync accepts but NO callback ever fires — no token, no
        // error, no EOS ("prompt 1 works, prompt 2 hangs forever"). On a
        // reused session prefill only encodes the NEW user message (the KV
        // prefix is already resident), so silence beyond this window is
        // pathological. Recovery is bounded: abort the wedged decode, retire
        // the session, reseed from history and resend once. Fresh sessions
        // are NOT covered here — their full-history prefill can legitimately
        // take longer; the repository init-stall watchdog remains the final
        // net for them.
        val stalledSessionWatchdog: Job? = if (lastSessionReused) {
            scope.launch {
                try {
                    delay(FIRST_CALLBACK_TIMEOUT_MS)
                    if (!anyCallbackSeen.get() && !completed.get() && !stopDetected.get()) {
                        logger.w(
                            "STALLED SESSION: no native callback within ${FIRST_CALLBACK_TIMEOUT_MS}ms of send on a " +
                                "REUSED conversation — recovering via bounded reseed"
                        )
                        retryOnFreshSession(callback, "no callback from reused session", false)
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Cancelled after first callback / flow end.
                }
            }
        } else null

        try {
            logger.i(
                "PREFILL START: session=${if (lastSessionReused) "REUSED (KV hit — only new message prefilled)" else "fresh (full history prefill)"} " +
                    "messages=${messages.size} delegate=${activeBackendInfo?.displayName ?: "?"}"
            )
            conv.sendMessageAsync(
                Message.user(last.content),
                callback,
                maxOutputToken = streamingMaxOutputTokens(config),
                thinkingConfig = thinkingConfig()
            )
        } catch (e: Throwable) {
            // The send never started: reset the generation slot + state HERE.
            // This early return skips the cleanup after awaitClose — without
            // this reset every later turn would wedge for the full drain
            // window on "generation already in progress".
            if (completed.compareAndSet(false, true)) {
                trySend(Result.Error(EngineException(e.message ?: "Chat generation failed", e)))
            }
            close()
            releaseRun()
            return@callbackFlow
        }
        try {
            awaitClose { }
        } catch (e: CancellationException) {
            runCatching { conv.cancelProcess() }
            runCatching { conversation?.close() }
            conversation = null
            consumedTurns = emptyList()
            logger.i("chat generation cancelled by collector after $tokenCount fragments")
            finishCleanly("cancelled", persistTranscript = false)
            throw e
        } finally {
            stalledSessionWatchdog?.cancel()
            if (!completed.get()) {
                logger.w("WARNING: chat stream exited WITHOUT a terminal EOS/error callback — forcing cleanup")
            }
            releaseRun()
        }
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
        logger.d(
            "SESSION CREATE: conversation on ${activeBackendInfo?.displayName ?: "active delegate"} " +
                "(the DELEGATE itself is NOT recreated — only this lightweight session)"
        )
        logger.i("Decoder Created: conversation on ${activeBackendInfo?.displayName ?: "active delegate"}")
        return EnginePerformanceMonitor.measure(EnginePerformanceMonitor.Stages.CONVERSATION_CREATE) {
            eng.createConversation(config)
        }
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
        npuLibDir: String?,
        maxNumTokens: Int?
    ): Pair<InferenceBackend, Long> {
        var lastError: Throwable? = null
        var attempted = 0
        logger.i(
            "DELEGATE LIFECYCLE begin: ${candidates.size} candidate(s) [${candidates.joinToString { it.displayName }}] " +
                "threads=$threads maxNumTokens=$maxNumTokens"
        )
        for (candidate in candidates) {
            attempted++
            // Skip backends that have failed too many consecutive times.
            if (EngineCrashGuard.isBackendDisabled(candidate.displayName)) {
                logger.w("DELEGATE SKIP: ${candidate.displayName} disabled after consecutive failures — skipping")
                continue
            }
            var newEngine: Engine? = null
            val startedAt = System.currentTimeMillis()
            try {
                logger.i(
                    "DELEGATE CREATE attempt $attempted/${candidates.size}: ${candidate.displayName} " +
                        "(delegate=${candidate.delegate}) — building interpreter"
                )
                newEngine = Engine(
                    LitertEngineConfig(
                        modelPath = path,
                        backend = candidate.toLiteRtBackend(threads, npuLibDir),
                        cacheDir = cacheDir,
                        maxNumTokens = maxNumTokens
                    )
                )
                newEngine.initialize()
                val initMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                if (attempted > 1 || engine != null) {
                    logger.w(
                        "DELEGATE SWAP: replacing ${engine?.let { "previous ${activeBackendInfo?.displayName ?: "delegate"}" } ?: "no"} " +
                            "interpreter with ${candidate.displayName}"
                    )
                    engine?.let { old ->
                        runCatching {
                            logger.i("DELEGATE DESTROY: closing previous interpreter")
                            old.close()
                        }
                    }
                }
                engine = newEngine
                // Record success so the backend is not disabled after a single failure.
                EngineCrashGuard.recordSuccess(candidate.displayName)
                logger.i(
                    "DELEGATE ACTIVE: ${candidate.displayName} initialized in ${initMs}ms — this delegate is " +
                        "reused for EVERY generation until the model is unloaded (never recreated per prompt)"
                )
                return candidate to initMs
            } catch (e: Throwable) {
                lastError = e
                runCatching { newEngine?.close() }
                EngineCrashGuard.recordCrash("backend_init", candidate.displayName, e)
                val msg = e.message ?: ""
                val unsupportedOps = msg.contains("op ", ignoreCase = true) ||
                    msg.contains("operator", ignoreCase = true) ||
                    msg.contains("not supported", ignoreCase = true) ||
                    msg.contains("unsupported", ignoreCase = true)
                logger.w(
                    "DELEGATE FAILED: ${candidate.displayName} (${candidate.delegate}) init failed after " +
                        "${System.currentTimeMillis() - startedAt}ms — ${if (unsupportedOps) "[UNSUPPORTED-OPERATOR SIGNATURE] " else ""}" +
                        "${e.javaClass.simpleName}: $msg — " +
                        "${if (candidates.size > attempted) "falling through to next candidate" else "NO more backends"}"
                )
            }
        }
        throw lastError ?: EngineException("No backend could be initialized")
    }

/**
     * Builds the post-generation record from LiteRT's own benchmark counters.
     * Callback fragments are not reliable token counts, so they are used only
     * when a cancelled/legacy delegate does not provide BenchmarkInfo.
     */
    @OptIn(ExperimentalApi::class)
    @Synchronized
    private fun buildGenerationStats(
        conversation: Conversation,
        totalTimeMs: Long,
        fallbackFirstTokenMs: Long,
        fallbackGeneratedTokens: Long,
        fallbackPromptTokens: Long = 0L,
        stopReason: String
    ): EngineStats {
        // The module compiles with -Xskip-metadata-version-check (the AAR
        // ships Kotlin 2.3.0 metadata), so BenchmarkInfo is read as a Kotlin
        // class: access its properties directly, not Java-style getters.
        val benchmark = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
        // Prompt tokens: prefer the runtime's own prefill counter; when a
        // backend does not report it, fall back to the caller's estimate so
        // the context meter never shows "Context 0 / N" after a successful
        // prompt insertion.
        val promptTokens = benchmark?.lastPrefillTokenCount?.toLong()?.takeIf { it > 0L }
            ?: fallbackPromptTokens.coerceAtLeast(0L)
        val generatedTokens = benchmark?.lastDecodeTokenCount?.toLong()?.takeIf { it > 0L }
            ?: fallbackGeneratedTokens
        val promptTokensPerSecond = benchmark?.lastPrefillTokensPerSecond
            ?.toFloat()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: 0f
        val decodeTokensPerSecond = benchmark?.lastDecodeTokensPerSecond
            ?.toFloat()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: if (generatedTokens > 0L) generatedTokens * 1000f / totalTimeMs.coerceAtLeast(1L) else 0f
        val promptTimeMs = durationFromRate(promptTokens, promptTokensPerSecond)
        val generationTimeMs = durationFromRate(generatedTokens, decodeTokensPerSecond)
        val firstTokenMs = benchmark?.timeToFirstTokenInSecond
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.times(1000.0)
            ?.roundToLong()
            ?: fallbackFirstTokenMs

        if (decodeTokensPerSecond > 0f) {
            decodeSpeedHistory.addLast(decodeTokensPerSecond)
            if (decodeSpeedHistory.size > MAX_GENERATION_SPEED_SAMPLES) decodeSpeedHistory.removeFirst()
            peakDecodeTokensPerSecond = maxOf(peakDecodeTokensPerSecond, decodeTokensPerSecond)
        }
        val averageTokensPerSecond = if (decodeSpeedHistory.isEmpty()) 0f
        else decodeSpeedHistory.average().toFloat()

        return applyBackendFields(
            EngineStats(
                promptTokens = promptTokens,
                generatedTokens = generatedTokens,
                promptTimeMs = promptTimeMs,
                generationTimeMs = generationTimeMs,
                totalTimeMs = totalTimeMs,
                tokensPerSecond = decodeTokensPerSecond,
                decodeTokensPerSecond = decodeTokensPerSecond,
                promptTokensPerSecond = promptTokensPerSecond,
                averageTokensPerSecond = averageTokensPerSecond,
                firstTokenMs = firstTokenMs,
                stopReason = stopReason
            ),
            peakTps = peakDecodeTokensPerSecond
        )
    }

    private fun durationFromRate(tokens: Long, tokensPerSecond: Float): Long =
        if (tokens > 0L && tokensPerSecond > 0f) {
            (tokens * 1000.0 / tokensPerSecond).roundToLong().coerceAtLeast(1L)
        } else {
            0L
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

    /** True when the last chat turn reused the resident session (KV hit). */
    @Volatile
    private var lastSessionReused: Boolean = false

    private fun ensureConversationForHistory(
        eng: Engine,
        messages: List<ChatPromptMessage>,
        config: GenerationConfig
    ): Conversation {
        val sessionLookupStartedAt = System.currentTimeMillis()
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
            lastSessionReused = true
            logger.i(
                "SESSION LOOKUP: KV-cache REUSE hit (${System.currentTimeMillis() - sessionLookupStartedAt}ms) — " +
                    "only the new user message will be prefilled"
            )
            return live
        }
        val reseedReason = when {
            reuseBroken.get() -> "template-drift latch"
            live == null -> "no resident session"
            conversationSystemPrompt != system -> "system prompt changed"
            else -> "history changed"
        }
        logger.i("SESSION LOOKUP: RESEED required ($reseedReason) — full history will be prefilled")
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
        val fresh = createConversationWithFamilyFlags(eng, conversationConfig).also {
            conversation = it
            conversationSystemPrompt = system
            consumedTurns = seed
        }
        lastSessionReused = false
        logger.i(
            "SESSION LOOKUP: reseed complete in ${System.currentTimeMillis() - sessionLookupStartedAt}ms " +
                "(seedTurns=${seed.size})"
        )
        return fresh
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
        // Capture the conversation reference atomically to avoid races
        // where another thread nulls it between our null-check and cancel.
        val conv = conversation ?: return@runCatching
        logger.i("cancel requested: aborting the active decode and retiring the conversation")
        try {
            runCatching { conv.cancelProcess() }
        } catch (e: Throwable) {
            // cancelProcess can throw if the conversation is in a bad state —
            // record but do not rethrow; we still need to clean up.
            EngineCrashGuard.recordCrash("cancel", "", e)
        }
        // A cancelled conversation is wedged — every later send fails with
        // "CANCELLED: Task cancelled". Close it so the next turn starts fresh.
        try {
            runCatching { conv.close() }
        } catch (e: Throwable) {
            EngineCrashGuard.recordCrash("cancel_cleanup", "", e)
        }
        // Only null out if this is still the same conversation we captured.
        // Another thread may have already replaced it (e.g. reseedAfterOverflow).
        if (conversation === conv) {
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
        // ISOLATED throwaway conversation (see [tokenStream]): a benchmark
        // must never inject its synthetic prompts into the resident CHAT
        // conversation's KV cache.
        var conv: Conversation? = null
        try {
            // createConversation + sendMessage are blocking native calls —
            // benchmark flows may be collected from any dispatcher, so run the
            // whole pass off the caller's thread.
            conv = withContext(Dispatchers.Default) {
                createConversationWithFamilyFlags(eng, conversationConfigForSampler(GenerationConfig()))
            }
            val activeConv = checkNotNull(conv)
            val prompt = "The quick brown fox jumps over the lazy dog."
            var best = 0f
            var sum = 0f
            for (i in 0 until iterations) {
                val startedAt = System.currentTimeMillis()
                withContext(Dispatchers.Default) {
                    activeConv.sendMessage(Message.user(prompt), maxOutputToken = 32)
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
            conv?.let { runCatching { it.close() } }
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
        val fit = lastFitDiagnostics
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
            modelLoadMs = lastModelLoadMs,
            warmupMs = lastWarmupMs,
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
            stopReason = stats?.stopReason ?: "",
            memoryMode = fit?.memoryMode ?: "",
            isAggressiveFit = fit?.isAggressiveFit ?: false,
            wasContextLowered = fit?.wasContextLowered ?: false,
            wasBackendChanged = fit?.wasBackendChanged ?: false,
            effectiveContext = fit?.effectiveContext ?: model.contextLength,
            requestedContext = fit?.requestedContext ?: model.contextLength,
            memoryReductions = fit?.reductionsApplied?.joinToString(", ") ?: "",
            fitDiagnostics = fit?.toLogLine() ?: ""
        )
    }

    override fun release() {
        stopRuntimeMetricsRefresh()
        logger.i("DELEGATE DESTROY: release — closing session + interpreter (${activeBackendInfo?.displayName ?: "unknown delegate"})")
        // Use crash guard to ensure every cleanup step runs even if one fails.
        EngineCrashGuard.guardOrNull("release_conversation") { conversation?.close() }
        conversation = null
        EngineCrashGuard.guardOrNull("release_engine") { engine?.close() }
        engine = null
        loadedModel = null
        loadedFilePath = null
        familyConfig = null
        outputDecoder = null
        generationActive.set(false)
        // Clear all caches to free memory when the engine is fully released.
        runCatching { ContainerMetadataReader.clearCache() }
        PrefixCache.invalidateAll()
        BufferPool.clear()
        EnginePerformanceMonitor.resetAll()
        EngineCrashGuard.reset()
        _engineState.value = EngineState.Unloaded
        _stats.value = null
        _memoryStats.value = null
    }

    /**
     * Takes one live memory snapshot. LiteRT-LM 0.16 exposes delegate identity
     * and generation benchmarks, but has no public allocator/Vulkan API. We
     * therefore report Android's actual process/heap counters and deliberately
     * leave unsupported delegate counters unset (their UI reads "Unavailable").
     */
    @OptIn(ExperimentalApi::class)
    /** Cached ActivityManager to avoid getSystemService() on every refresh. */
    @Volatile
    private var cachedActivityManager: ActivityManager? = null

    /** Cached PID array for getProcessMemoryInfo (reused every second). */
    private val myPidArray = intArrayOf(Process.myPid())

    /**
     * Takes one live memory snapshot. Optimized to minimize allocations
     * since this runs every second while a model is loaded.
     *
     * LiteRT-LM 0.16 exposes delegate identity and generation benchmarks,
     * but has no public allocator/Vulkan API. We therefore report Android's
     * actual process/heap counters and deliberately leave unsupported delegate
     * counters unset (their UI reads "Unavailable").
     */
    @OptIn(ExperimentalApi::class)
    private fun fetchMemoryStats(): MemoryStats? {
        val model = loadedModel ?: return null
        val backend = activeBackendInfo
        val fileSize = runCatching { File(model.filePath).length() }.getOrDefault(0L)
        val type = backend?.type ?: BackendType.CPU
        val runtime = Runtime.getRuntime()
        val nativeAllocated = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        val nativeHeapSize = runCatching { Debug.getNativeHeapSize() }.getOrDefault(0L)
        val javaCommitted = runtime.totalMemory().coerceAtLeast(0L)
        val javaUsed = (javaCommitted - runtime.freeMemory()).coerceAtLeast(0L)

        // PSS: cache the ActivityManager to avoid getSystemService() every second.
        val am = cachedActivityManager ?: run {
            val mgr = runCatching { context.getSystemService(ActivityManager::class.java) }.getOrNull()
            cachedActivityManager = mgr
            mgr
        }
        val processPss = runCatching {
            am?.getProcessMemoryInfo(myPidArray)
                ?.firstOrNull()
                ?.totalPss
                ?.toLong()
                ?.times(1024L)
        }.getOrNull()?.coerceAtLeast(0L) ?: 0L
peakProcessPssBytes = maxOf(peakProcessPssBytes, processPss)
        val gpuName = _backendCapabilities.value.gpuName ?: backend?.accelerator ?: "LiteRT GPU"

        // Live KV-cache occupancy from the active conversation. LiteRT-LM does
        // not expose the cache in bytes, so its token counter is the real
        // metric: 0 when no conversation exists yet (cache genuinely empty),
        // -1 only when the runtime cannot answer (never a fake 0).
        val kvCacheTokens = runCatching {
            conversation?.getTokenCount()?.toLong() ?: 0L
        }.getOrDefault(-1L)

        // Enrich with aggressive-fit diagnostics for the diagnostics panel
        val fit = lastFitDiagnostics
        val kvBytesEst = if (loadedModel != null) {
            io.androllm.engine.utils.MemoryEstimator.estimateKvCacheBytes(
                loadedModel!!.contextLength, 0, 0, 0, fileSize
            )
        } else 0L
        val weightsEst = io.androllm.engine.utils.MemoryEstimator.estimateWeightsMemory(fileSize)
        val availableEst = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            mi.availMem
        }.getOrDefault(0L)

        return MemoryStats(
            modelSizeBytes = fileSize,
            contextSizeBytes = kvBytesEst,
            peakMemoryBytes = peakProcessPssBytes,
            backend = type.name.lowercase(),
            backendReason = when (type) {
                BackendType.NPU -> "LiteRT NPU delegate active"
                BackendType.GPU -> "LiteRT GPU delegate active"
                else -> "CPU (XNNPACK)"
            } + if (fit?.isAggressiveFit == true) " (aggressive-fit: ${fit.memoryMode})" else "",
            gpuName = if (type == BackendType.GPU) gpuName else "",
            gpuInferenceVerified = type == BackendType.GPU,
            nativeHeapAllocatedBytes = nativeAllocated,
            nativeHeapSizeBytes = nativeHeapSize,
            javaHeapUsedBytes = javaUsed,
            javaHeapCommittedBytes = javaCommitted,
            processPssBytes = processPss,
            kvCacheTokens = kvCacheTokens,
            loadedSinceMs = modelLoadedAtMs,
            memoryMode = fit?.memoryMode ?: "",
            isAggressiveFit = fit?.isAggressiveFit ?: false,
            wasContextLowered = fit?.wasContextLowered ?: false,
            wasBackendChanged = fit?.wasBackendChanged ?: false,
            effectiveContext = fit?.effectiveContext ?: loadedModel?.contextLength ?: 0,
            requestedContext = fit?.requestedContext ?: loadedModel?.contextLength ?: 0,
            memoryReductions = fit?.reductionsApplied?.joinToString(", ") ?: "",
            fitDiagnostics = fit?.toLogLine() ?: "",
            estimatedWeightsMb = weightsEst / (1024f * 1024f),
            estimatedKvMb = kvBytesEst / (1024f * 1024f),
            estimatedTotalMb = (weightsEst + kvBytesEst) / (1024f * 1024f),
            availableRamMb = availableEst / (1024f * 1024f)
        )
    }

    private fun startRuntimeMetricsRefresh() {
        stopRuntimeMetricsRefresh()
        runtimeMetricsRefreshJob = scope.launch {
            while (isActive && isLoaded()) {
                _memoryStats.value = fetchMemoryStats()
                delay(RUNTIME_METRICS_REFRESH_MS)
            }
        }
    }

    private fun stopRuntimeMetricsRefresh() {
        runtimeMetricsRefreshJob?.cancel()
        runtimeMetricsRefreshJob = null
    }

    private fun publishReadyAfterGeneration() {
        val model = loadedModel ?: return
        promptCount++
        _engineState.value = EngineState.Ready(
            model = model,
            memoryStats = _memoryStats.value,
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

        /**
         * Bound for the one-time post-load warm-up inference. GPU graph
         * compilation is the expensive part (tens of seconds worst case on
         * slow Vulkan drivers); a compile that exceeds this budget is
         * aborted so the load always finishes (stalled-init requirement) —
         * the first prompt then pays the remaining cost with normal
         * watchdog protection.
         */
        private const val WARMUP_TIMEOUT_MS = 90_000L

        /**
         * Silence budget for a REUSED session after send: prefill only encodes the
         * new user message (KV prefix resident), so no callback within this window
         * means the continuation wedged. Triggers the bounded reseed recovery.
         */
        private const val FIRST_CALLBACK_TIMEOUT_MS = 25_000L

        /** Refresh live process counters once per second while a model is resident. */
        private const val RUNTIME_METRICS_REFRESH_MS = 1_000L
        private const val MAX_GENERATION_SPEED_SAMPLES = 64

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
        // PERFORMANCE: pre-compiled regex replaces all markers in a single pass
        // instead of N separate String.replace() calls per token.
        private val CONTROL_TOKEN_PATTERN = Regex(
            CONTROL_TOKEN_MARKERS.joinToString("|") { Regex.escape(it) }
        )

        fun stripControlTokens(text: String): String {
            if (text.isEmpty()) return text
            // Fast path: skip regex entirely when none of the marker chars exist.
            if (!CONTROL_TOKEN_MARKERS.any { it[0] in text }) return text
            return CONTROL_TOKEN_PATTERN.replace(text, "")
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
