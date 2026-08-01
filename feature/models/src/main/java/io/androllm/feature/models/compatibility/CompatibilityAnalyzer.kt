package io.androllm.feature.models.compatibility

import io.androllm.core.models.Model
import io.androllm.core.utils.DeviceHardwareInfo

enum class CompatibilityRating {
    EXCELLENT,
    MODERATE,
    INSUFFICIENT_RAM,
    UNSUPPORTED
}

data class CompatibilityAnalysis(
    val rating: CompatibilityRating,
    val title: String,
    val summary: String,
    val estimatedMemoryMb: Long,
    val estimatedTokPerSec: Float,
    val isRamSufficient: Boolean
)

object CompatibilityAnalyzer {

    fun analyze(model: Model, hardware: DeviceHardwareInfo): CompatibilityAnalysis {
        val totalRamGb = hardware.totalRamGb
        val minRamGb = model.minRamGb
        val recommendedRamGb = model.recommendedRamGb

        // Estimate memory usage from file size and context length
        val estMemoryBytes = model.fileSize + (model.contextLength * 1024L * 2)
        val estMemoryMb = estMemoryBytes / (1024 * 1024)

        val isRamSufficient = totalRamGb >= minRamGb

        val rating = when {
            totalRamGb < minRamGb -> CompatibilityRating.INSUFFICIENT_RAM
            totalRamGb >= recommendedRamGb -> CompatibilityRating.EXCELLENT
            else -> CompatibilityRating.MODERATE
        }

        val estimatedTokPerSec = when (rating) {
            CompatibilityRating.EXCELLENT -> if (hardware.cpuCores >= 8) 16.5f else 10.0f
            CompatibilityRating.MODERATE -> 6.5f
            CompatibilityRating.INSUFFICIENT_RAM -> 0.0f
            CompatibilityRating.UNSUPPORTED -> 0.0f
        }

        val (title, summary) = when (rating) {
            CompatibilityRating.EXCELLENT -> Pair(
                "✓ Excellent Compatibility",
                "Your device has ${"%.1f".format(totalRamGb)} GB RAM, exceeding the recommended ${"%.1f".format(recommendedRamGb)} GB."
            )
            CompatibilityRating.MODERATE -> Pair(
                "⚠ Moderate Performance Expected",
                "Your device meets the minimum ${"%.1f".format(minRamGb)} GB RAM requirement. Performance may slow down during long contexts."
            )
            CompatibilityRating.INSUFFICIENT_RAM -> Pair(
                "✕ Insufficient RAM",
                "Model requires at least ${"%.1f".format(minRamGb)} GB RAM. Your device only has ${"%.1f".format(totalRamGb)} GB RAM."
            )
            CompatibilityRating.UNSUPPORTED -> Pair(
                "✕ Unsupported Model",
                "This model format or architecture is incompatible with your system."
            )
        }

        return CompatibilityAnalysis(
            rating = rating,
            title = title,
            summary = summary,
            estimatedMemoryMb = estMemoryMb,
            estimatedTokPerSec = estimatedTokPerSec,
            isRamSufficient = isRamSufficient
        )
    }
}
