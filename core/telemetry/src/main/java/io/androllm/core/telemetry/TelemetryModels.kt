package io.androllm.core.telemetry

/**
 * A single 1-second snapshot of runtime telemetry, captured by
 * [TelemetryRepository] while any screen is sampling.
 *
 * Every field is backed by real runtime state:
 * - [tokensPerSecond] comes from the native engine's last [io.androllm.engine.models.EngineStats].
 * - [ramUsedMb]/[ramTotalMb] come from [android.app.ActivityManager] via DeviceInfoCollector.
 * - [gpuMemoryMb]/[kvCacheMb] come from native [io.androllm.engine.models.MemoryStats].
 * - [promptTokens]/[generatedTokens] come from the last native generation stats.
 */
data class TelemetrySample(
    val timestampMs: Long,
    val tokensPerSecond: Float,
    val ramUsedMb: Float,
    val ramTotalMb: Float,
    val gpuMemoryMb: Float,
    val kvCacheMb: Float,
    val promptTokens: Long,
    val generatedTokens: Long,
    val isGenerating: Boolean
)

/**
 * One completed generation, recorded when the engine emits new [io.androllm.engine.models.EngineStats].
 * Backs the generation-latency bar chart in Developer Mode.
 */
data class GenerationStat(
    val timestampMs: Long,
    val promptTokens: Long,
    val generatedTokens: Long,
    val tokensPerSecond: Float,
    val totalTimeMs: Long,
    val firstTokenMs: Long,
    val stopReason: String,
    val modelName: String
) {
    /** Total tokens processed in this generation (prompt + generated). */
    val totalTokens: Long get() = promptTokens + generatedTokens
}

/**
 * Current device + engine state snapshot for the Home dashboard.
 */
data class DeviceMetrics(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val cpuCores: Int,
    val deviceModel: String,
    val androidVersion: String,
    val isVulkanSupported: Boolean,
    val totalStorageBytes: Long,
    val usedStorageBytes: Long
) {
    val usedRamMb: Long get() = (totalRamMb - availableRamMb).coerceAtLeast(0L)
    val ramUsageFraction: Float get() = if (totalRamMb > 0) usedRamMb.toFloat() / totalRamMb else 0f
    val storageUsageFraction: Float get() = if (totalStorageBytes > 0) usedStorageBytes.toFloat() / totalStorageBytes else 0f
}

/**
 * Pure ring-buffer helper: appends [sample] to [history] keeping at most [max] entries.
 * Extracted for unit testing.
 */
fun <T> appendToHistory(history: List<T>, sample: T, max: Int): List<T> =
    (history + sample).takeLast(max.coerceAtLeast(1))
