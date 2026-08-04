package io.androllm.core.models.catalog

/**
 * Purely metadata-driven model recommendation for a device with [deviceRamGb] RAM.
 * No model ids or names are hardcoded - scoring is computed from catalog fields:
 * RAM fit, file size fit, quantization tier, popularity and curated flags.
 */
object RecommendationEngine {

    private const val WEIGHTS_GB_HEADROOM = 1.0
    private const val MAX_POPULARITY_FACTOR = 4.0

    data class ModelRecommendation(
        val model: CatalogModel,
        val score: Double,
        val reasons: List<String>
    )

    /**
     * Returns up to [topN] models ranked for [deviceRamGb] GB of device RAM.
     * Unhidden, ungated, non-archived models only. When nothing fits comfortably,
     * still returns the closest fits rather than nothing.
     */
    fun recommend(
        models: List<CatalogModel>,
        deviceRamGb: Float,
        topN: Int = 10
    ): List<ModelRecommendation> {
        if (models.isEmpty()) return emptyList()

        val scored = models
            .filter { !it.hidden && !it.isGated && it.statusValue != CatalogStatus.ARCHIVED }
            .map { model -> ModelRecommendation(model, score(model, deviceRamGb), reasons(model, deviceRamGb)) }
            .sortedByDescending { it.score }
        return scored.take(topN)
    }

    private fun score(model: CatalogModel, deviceRamGb: Float): Double {
        val sizeGb = model.sizeBytes / 1_000_000_000.0
        val headroomGb = deviceRamGb - model.recommendedRamGb
        val ramFit = when {
            headroomGb >= WEIGHTS_GB_HEADROOM -> 1.0
            headroomGb >= 0 -> 0.6 + 0.4 * (headroomGb / WEIGHTS_GB_HEADROOM)
            model.minRamGb <= deviceRamGb -> 0.3
            else -> 0.05
        }
        val sizeFit = if (sizeGb > 0) {
            when {
                sizeGb <= deviceRamGb * 0.9 -> 1.0
                sizeGb <= deviceRamGb -> 0.5
                else -> 0.1
            }
        } else 0.6

        val quantFit = when {
            deviceRamGb < 6 -> if (model.quantLevel.rank in 4..10) 1.0 else 0.5
            deviceRamGb < 10 -> if (model.quantLevel.rank in 9..13) 1.0 else 0.6
            else -> if (model.quantLevel.rank in 12..17) 1.0 else 0.75
        }

        val popularity = log1pPopularity(model).coerceAtMost(MAX_POPULARITY_FACTOR)

        val curated = when {
            model.recommended -> 0.5
            model.quantLevel.rank == 9 || model.quantLevel.rank == 10 -> 0.2
            else -> 0.0
        }

        return ramFit * 0.35 + sizeFit * 0.2 + quantFit * 0.2 + popularity * 0.15 + curated * 0.1
    }

    private fun log1pPopularity(model: CatalogModel): Double {
        val downloads = Math.log1p(model.downloads.toDouble())
        val likes = Math.log1p(model.likes.toDouble())
        val trending = Math.log1p(model.trendingScore.toDouble())
        return (downloads * 0.5 + likes * 0.3 + trending * 0.2) / 8.0
    }

    private fun reasons(model: CatalogModel, deviceRamGb: Float): List<String> {
        val reasons = mutableListOf<String>()
        val sizeGb = model.sizeBytes / 1_000_000_000.0
        if (model.minRamGb <= deviceRamGb) {
            reasons += if (model.recommendedRamGb <= deviceRamGb) {
                "Fits comfortably in ${deviceRamGb.toInt()} GB RAM"
            } else {
                "Runs within ${deviceRamGb.toInt()} GB RAM (tight fit)"
            }
        } else {
            reasons += "Needs ${model.minRamGb.toInt()} GB RAM minimum"
        }
        if (sizeGb > 0) reasons += "${formatGb(sizeGb)} file size"
        reasons += "${model.quantization} quantization"
        if (model.parameters.isNotBlank()) reasons += "${model.parameters} parameters"
        if (model.downloads > 0) reasons += "${model.downloads} downloads"
        return reasons
    }

    private fun formatGb(gb: Double): String =
        if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(gb * 1000)
}
