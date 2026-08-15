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

    private fun mb(bytes: Long): Long = bytes / (1024L * 1024L)
}
