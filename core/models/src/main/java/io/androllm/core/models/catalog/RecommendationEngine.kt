package io.androllm.core.models.catalog

/**
 * Device-aware model recommendation for the storage-streaming runtime.
 *
 * The core change vs the old engine: **storage size is no longer treated as
 * a RAM requirement.** [score] compares the *streaming RAM estimate*
 * ([CatalogModel.estimatedRuntimeRamMbValue], the resident working set) with
 * the device's available RAM, while [CatalogModel.sizeBytes] only expresses
 * the storage requirement. Models are categorized 🟢 Recommended / 🟡 Possible
 * / 🔴 Not recommended, but advanced users are never hard-blocked.
 */
object RecommendationEngine {

    private const val RAM_HEADROOM_GB = 1.0
    private const val MAX_POPULARITY_FACTOR = 4.0

    enum class Tier { RECOMMENDED, POSSIBLE, NOT_RECOMMENDED }

    data class ModelRecommendation(
        val model: CatalogModel,
        val score: Double,
        val reasons: List<String>,
        val tier: Tier,
    )

    /**
     * Ranks [models] for a device with [deviceRamGb] total RAM (available RAM
     * is derived with a safety margin). Returns up to [topN] unhidden,
     * ungated, non-archived models that the runtime supports.
     */
    fun recommend(
        models: List<CatalogModel>,
        deviceRamGb: Float,
        topN: Int = 10,
    ): List<ModelRecommendation> {
        if (models.isEmpty()) return emptyList()
        val availableGb = (deviceRamGb - 0.75f).coerceAtLeast(0.5f) // safety margin

        return models
            .filter { !it.hidden && !it.isGated && it.statusValue != CatalogStatus.ARCHIVED }
            .filter { it.isStreamable }
            .map { model ->
                val score = score(model, availableGb)
                ModelRecommendation(
                    model = model,
                    score = score,
                    reasons = reasons(model, availableGb),
                    tier = tier(model, availableGb),
                )
            }
            .sortedByDescending { it.score }
            .take(topN)
    }

    private fun score(model: CatalogModel, availableGb: Float): Double {
        val ramMb = model.estimatedRuntimeRamMbValue
        val ramGb = ramMb / 1000.0
        val headroom = availableGb - ramGb

        // RAM fit: streaming working set vs available RAM (the real constraint).
        val ramFit = when {
            headroom >= RAM_HEADROOM_GB -> 1.0
            headroom >= 0 -> 0.6 + 0.4 * (headroom / RAM_HEADROOM_GB)
            else -> 0.15
        }

        // Storage fit: the model file must be installable, but it is NOT RAM.
        val sizeGb = model.sizeBytes / 1_000_000_000.0
        val storageFit = if (sizeGb > 0) {
            when {
                sizeGb <= availableGb * 3 -> 1.0
                sizeGb <= availableGb * 5 -> 0.6
                else -> 0.3
            }
        } else 0.6

        val quantFit = when {
            availableGb < 5 -> if (model.quantLevel.rank in 4..10) 1.0 else 0.5
            availableGb < 9 -> if (model.quantLevel.rank in 9..13) 1.0 else 0.6
            else -> if (model.quantLevel.rank in 12..17) 1.0 else 0.75
        }

        val popularity = log1pPopularity(model).coerceAtMost(MAX_POPULARITY_FACTOR)
        val curated = when {
            model.recommended -> 0.5
            model.quantLevel.rank == 9 || model.quantLevel.rank == 10 -> 0.2
            else -> 0.0
        }

        // Continuous comfort term breaks ties between models that both fit:
        // the one leaving more RAM headroom ranks higher.
        val comfort = (1.0 - ramGb / availableGb.coerceAtLeast(0.1f).toDouble()).coerceIn(0.0, 1.0)

        return ramFit * 0.4 + storageFit * 0.15 + quantFit * 0.2 + popularity * 0.15 +
            curated * 0.1 + comfort * 0.05
    }

    private fun tier(model: CatalogModel, availableGb: Float): Tier {
        val ramGb = model.estimatedRuntimeRamMbValue / 1000.0
        return when {
            ramGb <= availableGb - RAM_HEADROOM_GB -> Tier.RECOMMENDED
            ramGb <= availableGb -> Tier.POSSIBLE
            else -> Tier.NOT_RECOMMENDED
        }
    }

    private fun log1pPopularity(model: CatalogModel): Double {
        val downloads = Math.log1p(model.downloads.toDouble())
        val likes = Math.log1p(model.likes.toDouble())
        val trending = Math.log1p(model.trendingScore.toDouble())
        return (downloads * 0.5 + likes * 0.3 + trending * 0.2) / 8.0
    }

    private fun reasons(model: CatalogModel, availableGb: Float): List<String> {
        val reasons = mutableListOf<String>()
        val ramMb = model.estimatedRuntimeRamMbValue
        val ramGb = ramMb / 1000.0
        reasons += if (ramGb <= availableGb) {
            "Estimated runtime RAM: ${formatMb(ramMb)} (streams from storage)"
        } else {
            "Estimated runtime RAM: ${formatMb(ramMb)} — heavy for this device"
        }
        val sizeGb = model.sizeBytes / 1_000_000_000.0
        if (sizeGb > 0) reasons += "Storage: ${formatGb(sizeGb)} (weights stay on disk)"
        reasons += "${model.quantization} quantization"
        if (model.parameters.isNotBlank()) reasons += "${model.parameters} parameters"
        reasons += "Backends: ${model.backendValues.joinToString { it.displayName }}"
        if (model.recommendedContext > 0) reasons += "Default context ${model.recommendedContext}"
        if (model.downloads > 0) reasons += "${model.downloads} downloads"
        return reasons
    }

    private fun formatGb(gb: Double): String =
        if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(gb * 1000)

    private fun formatMb(mb: Long): String =
        if (mb >= 1000) "%.1f GB".format(mb / 1000.0) else "$mb MB"
}
