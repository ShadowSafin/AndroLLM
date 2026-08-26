package io.androllm.engine.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * Outcome of a pre-load resource check.
 */
sealed interface ResourceCheck {
    /** The model is estimated to fit with safe headroom. */
    data object Allowed : ResourceCheck

    /**
     * Loading would risk system-wide memory pressure (OOM kill, device
     * freeze). [reason] is user-presentable and names the actual numbers.
     */
    data class Insufficient(
        val neededBytes: Long,
        val availableBytes: Long,
        val reason: String
    ) : ResourceCheck
}

/**
 * Aggressive memory profile — controls how hard the engine tries to fit
 * a large model before refusing. Qwen3 8B defaults to [AGGRESSIVE_FIT].
 */
enum class MemoryMode(
    val label: String,
    val safetyFraction: Double,
    val minHeadroomBytes: Long,
    val scratchFraction: Float,
    val useCompactTokenizer: Boolean,
    val batchSize: Int
) {
    /** Maximum speed — larger scratch, keep GPU/NPU. */
    PERFORMANCE(
        label = "performance",
        safetyFraction = 0.70,
        minHeadroomBytes = 256L * 1024 * 1024,
        scratchFraction = MemoryEstimator.COMPUTE_SCRATCH_FRACTION,
        useCompactTokenizer = false,
        batchSize = 2048
    ),
    /** Balanced — good speed, moderate memory savings. */
    BALANCED(
        label = "balanced",
        safetyFraction = 0.75,
        minHeadroomBytes = 192L * 1024 * 1024,
        scratchFraction = MemoryEstimator.COMPUTE_SCRATCH_FRACTION_BALANCED,
        useCompactTokenizer = false,
        batchSize = 1024
    ),
    /**
     * Aggressive-fit — tries hardest to squeeze large models (Qwen3 8B)
     * by reducing scratch, using compact buffers, smaller batch and
     * shorter context. Higher safety fraction (0.85) reflects that the
     * runtime is more efficient than the worst-case estimate.
     */
    AGGRESSIVE_FIT(
        label = "aggressive-fit",
        safetyFraction = 0.85,
        minHeadroomBytes = 128L * 1024 * 1024,
        scratchFraction = MemoryEstimator.COMPUTE_SCRATCH_FRACTION_AGGRESSIVE,
        useCompactTokenizer = true,
        batchSize = 512
    ),
    /** Lowest memory — minimal context (1024) + all savings. */
    LOW_MEMORY(
        label = "low-memory",
        safetyFraction = 0.88,
        minHeadroomBytes = 96L * 1024 * 1024,
        scratchFraction = 0.06f,
        useCompactTokenizer = true,
        batchSize = 256
    )
}

/**
 * Diagnostics of one fit attempt — exposed to UI / logs when the model
 * is loaded via aggressive-fit or refused after optimization.
 */
data class MemoryFitDiagnostics(
    val totalAvailableBytes: Long,
    val fileSizeBytes: Long,
    val requestedContext: Int,
    val effectiveContext: Int,
    val requestedBackend: String,
    val effectiveBackend: String,
    val estimatedWeightsBytes: Long,
    val estimatedKvBytes: Long,
    val estimatedScratchBytes: Long,
    val estimatedTokenizerBytes: Long,
    val estimatedDelegateBytes: Long,
    val estimatedTotalBytes: Long,
    val budgetBytes: Long,
    val memoryMode: String,
    val isAggressiveFit: Boolean,
    val wasContextLowered: Boolean,
    val wasBackendChanged: Boolean,
    val wasBatchReduced: Boolean,
    val reductionsApplied: List<String>,
    val modelCategory: String,
    val fitSucceeded: Boolean,
    val rejectionReason: String? = null
) {
    fun toLogLine(): String = buildString {
        append("MemFit[avail=${totalAvailableBytes/1_048_576}MB ")
        append("need=${estimatedTotalBytes/1_048_576}MB budget=${budgetBytes/1_048_576}MB ")
        append("mode=$memoryMode ctx=$requestedContext->$effectiveContext ")
        append("backend=$requestedBackend->$effectiveBackend ")
        append("weights=${estimatedWeightsBytes/1_048_576}MB kv=${estimatedKvBytes/1_048_576}MB scratch=${estimatedScratchBytes/1_048_576}MB ")
        append("reductions=${reductionsApplied.joinToString(",")} fit=$fitSucceeded]")
    }
    fun userMessage(): String = when {
        fitSucceeded && wasContextLowered -> "Loaded with reduced context ($effectiveContext vs $requestedContext) to fit available RAM — ${reductionsApplied.joinToString(", ")}."
        fitSucceeded && reductionsApplied.isNotEmpty() -> "Loaded with memory optimizations: ${reductionsApplied.joinToString(", ")}."
        fitSucceeded -> "Model fits within available RAM."
        else -> rejectionReason ?: "Model exceeds safe memory limits."
    }
}

/**
 * Result of an aggressive-fit attempt — either the model can be loaded
 * with an adjusted config, or it truly cannot fit even after all savings.
 */
sealed interface AggressiveFitResult {
    data class Fit(
        val diagnostics: MemoryFitDiagnostics,
        val effectiveContext: Int,
        val effectiveBackend: io.androllm.engine.models.BackendType,
        val effectiveMode: MemoryMode,
        val effectiveScratchFraction: Float,
        val effectiveBatchSize: Int,
        val reductions: List<String>
    ) : AggressiveFitResult
    data class NoFit(
        val diagnostics: MemoryFitDiagnostics,
        val attemptedConfigs: List<String>
    ) : AggressiveFitResult
}

/**
 * Pre-load safety gate for llama.cpp models.
 *
 * llama.cpp allocates NATIVE memory (mmap + heap + GPU buffers) far beyond the
 * app's Java heap, so the only meaningful budget is SYSTEM RAM. Before a model
 * is handed to the native loader this guard:
 *
 * 1. estimates the footprint (weights + layer-accurate KV cache + compute
 *    scratch) via [MemoryEstimator],
 * 2. measures currently available RAM ([ActivityManager] `availMem` minus the
 *    native heap already committed by this process),
 * 3. refuses the load with a clear, numeric explanation when the estimate does
 *    not fit with a safety margin — or when the system already reports low
 *    memory.
 *
 * The refusal happens BEFORE any native allocation, so a device can never be
 * frozen by an oversized model ("If the model cannot run safely, explain why
 * instead of crashing").
 */
/**
 * DI is provided by [io.androllm.engine.di.EngineModule.provideModelResourceGuard];
 * the public no-arg secondary constructor exists so the pure decision logic
 * ([checkAgainst]) is unit-testable without Android/Hilt.
 */
class ModelResourceGuard internal constructor(
    private val context: Context?
) {

    /** Test/standalone construction: context-dependent helpers no-op on null. */
    constructor() : this(null)


    /**
     * Safety margin: we never want to consume more than this fraction of the
     * currently available RAM (leaves headroom for the OS, other apps, and the
     * app's own Java heap). 0.7 keeps 30% of free RAM untouched.
     */
    private val SAFETY_FRACTION = 0.7

    /**
     * Hard floor below which a load is always refused (prevents a pathological
     * tiny available-RAM reading from being "fit").
     */
    private val MIN_HEADROOM_BYTES = 256L * 1024 * 1024

    /** Aggressive-fit floor (tighter because runtime is more efficient than worst-case). */
    private val MIN_HEADROOM_AGGRESSIVE = 128L * 1024 * 1024
    private val MIN_HEADROOM_LOW_MEMORY = 96L * 1024 * 1024
    private val SAFETY_FRACTION_AGGRESSIVE = 0.85
    private val SAFETY_FRACTION_LOW_MEMORY = 0.88

    /**
     * The complete native footprint of a model + context, using the GGUF
     * header's transformer geometry when available.
     */
    fun estimateFootprint(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0
    ): Long = MemoryEstimator.estimateTotalFootprint(
        fileSizeBytes = fileSizeBytes,
        contextLength = contextLength.coerceAtLeast(1024),
        blockCount = blockCount,
        headCountKv = headCountKv,
        keyLength = keyLength
    )

    fun estimateDetailedFootprint(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0,
        backend: io.androllm.engine.models.BackendType = io.androllm.engine.models.BackendType.CPU,
        mode: MemoryMode = MemoryMode.BALANCED
    ): Long = MemoryEstimator.estimateDetailedFootprint(
        fileSizeBytes = fileSizeBytes,
        contextLength = contextLength,
        blockCount = blockCount,
        headCountKv = headCountKv,
        keyLength = keyLength,
        backend = backend,
        scratchFraction = mode.scratchFraction,
        includeTokenizer = true,
        compactTokenizer = mode.useCompactTokenizer,
        includeBackend = true,
        compactBackend = mode == MemoryMode.AGGRESSIVE_FIT || mode == MemoryMode.LOW_MEMORY,
        batchSize = mode.batchSize
    )

    private fun footprintFor(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int,
        headCountKv: Int,
        keyLength: Int,
        backend: io.androllm.engine.models.BackendType,
        mode: MemoryMode
    ): Long {
        val weights = MemoryEstimator.estimateWeightsMemory(fileSizeBytes)
        val kv = MemoryEstimator.estimateKvCacheBytes(contextLength, blockCount, headCountKv, keyLength, fileSizeBytes)
        val scratch = MemoryEstimator.estimateComputeScratchForFraction(weights, mode.scratchFraction)
        val tok = MemoryEstimator.estimateTokenizerOverhead(mode.useCompactTokenizer)
        val delegate = MemoryEstimator.estimateBackendOverhead(backend, mode == MemoryMode.AGGRESSIVE_FIT || mode == MemoryMode.LOW_MEMORY)
        return weights + kv + scratch + tok + delegate
    }

    /**
     * System RAM currently available to this process's native allocations:
     * device-wide `availMem` minus the native heap this process already
     * committed. Floor at 0.
     */
    fun availableRamBytes(): Long {
        val ctx = context ?: return 0L
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val avail = if (memInfo.availMem > 0) memInfo.availMem else 0L
        val nativeHeapUsed = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        return (avail - nativeHeapUsed).coerceAtLeast(0L)
    }

    /** True when the OS is under memory pressure (low-memory killer active). */
    fun isSystemLowMemory(): Boolean {
        val ctx = context ?: return false
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        return memInfo.lowMemory
    }

    /**
     * Runs the gate against the live device state. Returns
     * [ResourceCheck.Allowed] only when the estimated footprint fits inside
     * `SAFETY_FRACTION` of the available RAM with at least [MIN_HEADROOM_BYTES]
     * to spare, and the system is not in a low-memory state.
     */
    fun check(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0
    ): ResourceCheck = checkAgainst(
        availableBytes = availableRamBytes(),
        lowMemory = isSystemLowMemory(),
        fileSizeBytes = fileSizeBytes,
        contextLength = contextLength,
        blockCount = blockCount,
        headCountKv = headCountKv,
        keyLength = keyLength
    )

    /**
     * Pure decision logic (no Android framework) so the exact thresholds are
     * unit-testable. [availableBytes] is the RAM this process may use; see
     * [availableRamBytes] for the production measurement.
     */
    fun checkAgainst(
        availableBytes: Long,
        lowMemory: Boolean,
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0
    ): ResourceCheck {
        val needed = estimateFootprint(fileSizeBytes, contextLength, blockCount, headCountKv, keyLength)

        if (lowMemory) {
            return ResourceCheck.Insufficient(
                neededBytes = needed,
                availableBytes = availableBytes,
                reason = "System memory is low — loading this model could freeze the device. " +
                    "Free up memory or choose a smaller model."
            )
        }

        val budget = (availableBytes * SAFETY_FRACTION).toLong()
        if (needed > budget || (budget - needed) < MIN_HEADROOM_BYTES) {
            return ResourceCheck.Insufficient(
                neededBytes = needed,
                availableBytes = availableBytes,
                reason = "Model needs ~${mb(needed)} MB of RAM (weights + KV cache + compute), " +
                    "but only ~${mb(availableBytes)} MB is free. It cannot run safely on this device — " +
                    "choose a smaller model or a shorter context."
            )
        }
        return ResourceCheck.Allowed
    }

    // ── Aggressive-fit orchestration ─────────────────────────────────────

    /**
     * Attempts to fit a model aggressively before refusing. Tries progressively
     * smaller configurations (reduced context, balanced → aggressive scratch,
     * compact backend, CPU fallback) and only refuses when none fit even with
     * the smallest safe settings. Returns a [AggressiveFitResult] with full
     * diagnostics for logging and user feedback.
     */
    fun attemptAggressiveFit(
        fileSizeBytes: Long,
        contextLength: Int,
        availableBytes: Long,
        lowMemory: Boolean,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0,
        requestedBackend: io.androllm.engine.models.BackendType = io.androllm.engine.models.BackendType.CPU,
        quantization: String? = null
    ): AggressiveFitResult {
        val requestedCtx = contextLength.coerceAtLeast(512)
        val category = MemoryEstimator.modelCategory(fileSizeBytes)
        val isLarge = MemoryEstimator.isLargeModel(fileSizeBytes)
        val isXLarge = MemoryEstimator.isXLargeModel(fileSizeBytes)

        // Build ladder of context options to try — large models start lower.
        val ctxLadder = buildContextLadder(requestedCtx, isLarge, isXLarge)
        val backendOptions = buildBackendOptions(requestedBackend)
        val modeLadder = when {
            isXLarge -> listOf(MemoryMode.BALANCED, MemoryMode.AGGRESSIVE_FIT, MemoryMode.LOW_MEMORY)
            isLarge -> listOf(MemoryMode.BALANCED, MemoryMode.AGGRESSIVE_FIT, MemoryMode.LOW_MEMORY)
            else -> listOf(MemoryMode.BALANCED, MemoryMode.AGGRESSIVE_FIT)
        }

        val attempted = mutableListOf<String>()

        // 1) Try the requested config first (respect user preference)
        for (mode in listOf(MemoryMode.PERFORMANCE, MemoryMode.BALANCED)) {
            for (backend in backendOptions.take(1)) {
                for (ctx in listOf(requestedCtx)) {
                    val needed = footprintFor(fileSizeBytes, ctx, blockCount, headCountKv, keyLength, backend, mode)
                    val budget = (availableBytes * mode.safetyFraction).toLong()
                    val headroom = budget - needed
                    attempted.add("ctx=$ctx backend=${backend.name} mode=${mode.label} need=${mb(needed)}MB")
                    if (!lowMemory && needed <= budget && headroom >= mode.minHeadroomBytes) {
                        return successResult(fileSizeBytes, requestedCtx, ctx, requestedBackend, backend, needed, budget, mode, emptyList(), availableBytes)
                    }
                    if (lowMemory) {
                        // In lowMemory, require aggressive thresholds even for default mode — otherwise continue to aggressive loop
                        val aggressiveOk = needed <= (availableBytes * SAFETY_FRACTION_AGGRESSIVE).toLong() &&
                            ((availableBytes * SAFETY_FRACTION_AGGRESSIVE).toLong() - needed) >= MIN_HEADROOM_AGGRESSIVE
                        if (aggressiveOk) {
                            return successResult(fileSizeBytes, requestedCtx, ctx, requestedBackend, backend, needed, (availableBytes * SAFETY_FRACTION_AGGRESSIVE).toLong(), MemoryMode.AGGRESSIVE_FIT, listOf("low-memory: aggressive budget applied"), availableBytes)
                        }
                    }
                }
            }
        }

        // 2) Aggressive ladder: reduced context + aggressive modes + CPU fallback
        for (mode in modeLadder) {
            for (backend in backendOptions) {
                for (ctx in ctxLadder) {
                    val needed = footprintFor(fileSizeBytes, ctx, blockCount, headCountKv, keyLength, backend, mode)
                    val budget = (availableBytes * mode.safetyFraction).toLong()
                    val headroom = budget - needed
                    attempted.add("ctx=$ctx backend=${backend.name} mode=${mode.label} need=${mb(needed)}MB")
                    val lowMemOk = if (lowMemory) {
                        needed <= (availableBytes * mode.safetyFraction).toLong() &&
                            headroom >= (mode.minHeadroomBytes / 2).coerceAtLeast(MIN_HEADROOM_LOW_MEMORY / 2)
                    } else true
                    if (needed <= budget && headroom >= mode.minHeadroomBytes / 2 && lowMemOk) {
                        val reductions = buildReductions(requestedCtx, ctx, requestedBackend, backend, mode)
                        return successResult(fileSizeBytes, requestedCtx, ctx, requestedBackend, backend, needed, budget, mode, reductions, availableBytes)
                    }
                }
            }
        }

        // Still no fit — build diagnostics for the smallest possible config
        val smallestCtx = ctxLadder.lastOrNull() ?: 1024
        val smallestBackend = backendOptions.lastOrNull() ?: io.androllm.engine.models.BackendType.CPU
        val smallestMode = MemoryMode.LOW_MEMORY
        val smallestNeeded = footprintFor(fileSizeBytes, smallestCtx, blockCount, headCountKv, keyLength, smallestBackend, smallestMode)
        val budget = (availableBytes * smallestMode.safetyFraction).toLong()
        val breakdown = BreakdownForDiagnostics(fileSizeBytes, smallestCtx, blockCount, headCountKv, keyLength, smallestBackend, smallestMode)
        val diag = MemoryFitDiagnostics(
            totalAvailableBytes = availableBytes,
            fileSizeBytes = fileSizeBytes,
            requestedContext = requestedCtx,
            effectiveContext = smallestCtx,
            requestedBackend = requestedBackend.name,
            effectiveBackend = smallestBackend.name,
            estimatedWeightsBytes = MemoryEstimator.estimateWeightsMemory(fileSizeBytes),
            estimatedKvBytes = MemoryEstimator.estimateKvCacheBytes(smallestCtx, blockCount, headCountKv, keyLength, fileSizeBytes),
            estimatedScratchBytes = MemoryEstimator.estimateComputeScratchForFraction(MemoryEstimator.estimateWeightsMemory(fileSizeBytes), smallestMode.scratchFraction),
            estimatedTokenizerBytes = MemoryEstimator.estimateTokenizerOverhead(smallestMode.useCompactTokenizer),
            estimatedDelegateBytes = MemoryEstimator.estimateBackendOverhead(smallestBackend, true),
            estimatedTotalBytes = smallestNeeded,
            budgetBytes = budget,
            memoryMode = smallestMode.label,
            isAggressiveFit = true,
            wasContextLowered = smallestCtx != requestedCtx,
            wasBackendChanged = smallestBackend != requestedBackend,
            wasBatchReduced = smallestMode.batchSize < MemoryMode.PERFORMANCE.batchSize,
            reductionsApplied = buildReductions(requestedCtx, smallestCtx, requestedBackend, smallestBackend, smallestMode),
            modelCategory = category,
            fitSucceeded = false,
            rejectionReason = buildRejectionMessage(fileSizeBytes, smallestNeeded, availableBytes, requestedCtx, smallestCtx, isXLarge, requestedBackend)
        )
        return AggressiveFitResult.NoFit(diagnostics = diag, attemptedConfigs = attempted)
    }

    /**
     * Convenience overload that uses live device RAM. Returns Allowed with
     * an adjusted config when aggressive fit succeeds, otherwise Insufficient
     * with a detailed post-optimization message.
     */
    fun checkWithAggressiveFit(
        fileSizeBytes: Long,
        contextLength: Int,
        blockCount: Int = 0,
        headCountKv: Int = 0,
        keyLength: Int = 0,
        requestedBackend: io.androllm.engine.models.BackendType = io.androllm.engine.models.BackendType.CPU
    ): Pair<ResourceCheck, AggressiveFitResult?> {
        val available = availableRamBytes()
        val lowMem = isSystemLowMemory()
        val result = attemptAggressiveFit(fileSizeBytes, contextLength, available, lowMem, blockCount, headCountKv, keyLength, requestedBackend)
        return when (result) {
            is AggressiveFitResult.Fit -> ResourceCheck.Allowed to result
            is AggressiveFitResult.NoFit -> {
                ResourceCheck.Insufficient(
                    neededBytes = result.diagnostics.estimatedTotalBytes,
                    availableBytes = available,
                    reason = result.diagnostics.rejectionReason ?: "Model exceeds safe memory limits."
                ) to result
            }
        }
    }

    private fun buildContextLadder(requested: Int, isLarge: Boolean, isXLarge: Boolean): List<Int> {
        // Ladder derived from spec: progressively smaller safe sizes.
        // Large models (7B/8B) use a more compact ladder.
        val base = when {
            isXLarge -> listOf(4096, 3072, 2048, 1536, 1024)
            isLarge -> listOf(4096, 3072, 2048, 1536, 1024)
            requested > 8192 -> listOf(8192, 6144, 4096, 3072, 2048, 1024)
            requested > 4096 -> listOf(4096, 3072, 2048, 1536, 1024)
            requested > 2048 -> listOf(requested, 2048, 1536, 1024)
            else -> listOf(requested, 1024)
        }
        // Ensure ladder respects requested max and contains small safe floor
        val filtered = base.filter { it <= requested }.distinct().sortedDescending()
        val withFloor = if (filtered.lastOrNull() != 1024 && 1024 <= requested) filtered + 1024 else filtered
        // For very large request, also allow 512 as last resort
        val final = if (requested > 1024 && withFloor.last() == 1024) withFloor else withFloor
        return final + listOf(512).filter { it < (final.lastOrNull() ?: 1024) && requested > 512 }
    }

    private fun buildBackendOptions(requested: io.androllm.engine.models.BackendType): List<io.androllm.engine.models.BackendType> {
        // Explicit selections are exclusive (spec: never silently swap user's delegate).
        // AUTO may traverse NPU→GPU→CPU for memory efficiency; the caller (LiteRtLmEngine)
        // supplies the estimated AUTO backend as GPU for sizing, and the actual fallback chain
        // (BackendSelector.orderedCandidates) still handles NPU when it exists.
        return when (requested) {
            io.androllm.engine.models.BackendType.GPU -> listOf(io.androllm.engine.models.BackendType.GPU)
            io.androllm.engine.models.BackendType.NPU -> listOf(io.androllm.engine.models.BackendType.NPU)
            io.androllm.engine.models.BackendType.CPU -> listOf(io.androllm.engine.models.BackendType.CPU)
            io.androllm.engine.models.BackendType.AUTO -> listOf(io.androllm.engine.models.BackendType.GPU, io.androllm.engine.models.BackendType.CPU)
            else -> listOf(requested)
        }
    }

    private fun buildReductions(
        requestedCtx: Int,
        effectiveCtx: Int,
        requestedBackend: io.androllm.engine.models.BackendType,
        effectiveBackend: io.androllm.engine.models.BackendType,
        mode: MemoryMode
    ): List<String> {
        val list = mutableListOf<String>()
        if (effectiveCtx != requestedCtx) list.add("context ${requestedCtx}→${effectiveCtx}")
        if (effectiveBackend != requestedBackend) list.add("backend ${requestedBackend.name}→${effectiveBackend.name}")
        if (mode != MemoryMode.PERFORMANCE) list.add("mode ${mode.label}")
        list.add("batch ${mode.batchSize}")
        if (mode.useCompactTokenizer) list.add("compact-tokenizer")
        list.add("scratch ${(mode.scratchFraction * 100).toInt()}%")
        return list
    }

    private fun buildRejectionMessage(
        fileSizeBytes: Long,
        needed: Long,
        available: Long,
        requestedCtx: Int,
        attemptedCtx: Int,
        isXLarge: Boolean,
        requestedBackend: io.androllm.engine.models.BackendType? = null
    ): String {
        val prefix = "This model still exceeds safe memory limits after optimization."
        val ctxNote = if (attemptedCtx != requestedCtx) " Reduced-context mode was attempted (down to $attemptedCtx), but the model still needs ~${mb(needed)} MB with only ~${mb(available)} MB free." else " Even with reduced context ($attemptedCtx), it needs ~${mb(needed)} MB but only ~${mb(available)} MB is free."
        val backendHint = if (requestedBackend != null && requestedBackend != io.androllm.engine.models.BackendType.CPU && requestedBackend != io.androllm.engine.models.BackendType.AUTO) {
            " The selected ${requestedBackend.name} backend was kept as requested — switch to Auto to allow an automatic memory-efficient fallback."
        } else ""
        val hint = when {
            isXLarge -> " Try a lower quantization (Q4_K_M) or a smaller context length, or close background apps."
            fileSizeBytes > 3_800L * 1024 * 1024 -> " Try a lower quantization or a smaller context length (1024–2048)."
            else -> " Try a smaller context length or close background apps."
        }
        return prefix + ctxNote + backendHint + hint
    }

    private fun successResult(
        fileSizeBytes: Long,
        requestedCtx: Int,
        effectiveCtx: Int,
        requestedBackend: io.androllm.engine.models.BackendType,
        effectiveBackend: io.androllm.engine.models.BackendType,
        needed: Long,
        budget: Long,
        mode: MemoryMode,
        extraReductions: List<String>,
        available: Long
    ): AggressiveFitResult.Fit {
        val weights = MemoryEstimator.estimateWeightsMemory(fileSizeBytes)
        val kv = MemoryEstimator.estimateKvCacheBytes(effectiveCtx, 0, 0, 0, fileSizeBytes) // fallback geometry; caller may have geometry
        val scratch = MemoryEstimator.estimateComputeScratchForFraction(weights, mode.scratchFraction)
        val tok = MemoryEstimator.estimateTokenizerOverhead(mode.useCompactTokenizer)
        val delegate = MemoryEstimator.estimateBackendOverhead(effectiveBackend, mode == MemoryMode.AGGRESSIVE_FIT || mode == MemoryMode.LOW_MEMORY)
        val reductions = buildReductions(requestedCtx, effectiveCtx, requestedBackend, effectiveBackend, mode) + extraReductions
        val diag = MemoryFitDiagnostics(
            totalAvailableBytes = available,
            fileSizeBytes = fileSizeBytes,
            requestedContext = requestedCtx,
            effectiveContext = effectiveCtx,
            requestedBackend = requestedBackend.name,
            effectiveBackend = effectiveBackend.name,
            estimatedWeightsBytes = weights,
            estimatedKvBytes = kv,
            estimatedScratchBytes = scratch,
            estimatedTokenizerBytes = tok,
            estimatedDelegateBytes = delegate,
            estimatedTotalBytes = needed,
            budgetBytes = budget,
            memoryMode = mode.label,
            isAggressiveFit = mode == MemoryMode.AGGRESSIVE_FIT || mode == MemoryMode.LOW_MEMORY,
            wasContextLowered = effectiveCtx != requestedCtx,
            wasBackendChanged = effectiveBackend != requestedBackend,
            wasBatchReduced = mode.batchSize < MemoryMode.PERFORMANCE.batchSize,
            reductionsApplied = reductions.distinct(),
            modelCategory = MemoryEstimator.modelCategory(fileSizeBytes),
            fitSucceeded = true
        )
        return AggressiveFitResult.Fit(
            diagnostics = diag,
            effectiveContext = effectiveCtx,
            effectiveBackend = effectiveBackend,
            effectiveMode = mode,
            effectiveScratchFraction = mode.scratchFraction,
            effectiveBatchSize = mode.batchSize,
            reductions = reductions.distinct()
        )
    }

    // Helper data for diagnostics breakdown when geometry known
    private data class BreakdownForDiagnostics(
        val fileSizeBytes: Long,
        val ctx: Int,
        val blockCount: Int,
        val headKv: Int,
        val keyLen: Int,
        val backend: io.androllm.engine.models.BackendType,
        val mode: MemoryMode
    )

    private fun mb(bytes: Long): Long = bytes / (1024L * 1024L)
}
