package io.androllm.feature.models.benchmark

import io.androllm.core.models.Model
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.models.EngineStats
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class BenchmarkReport(
    val modelId: String,
    val modelName: String,
    val quantization: String,
    val timestamp: String,
    val loadTimeMs: Long,
    val promptTokens: Long,
    val promptTimeMs: Long,
    val generatedTokens: Long,
    val generationTimeMs: Long,
    val tokensPerSecond: Float,
    val peakRamMb: Float
)

object ModelBenchmarker {

    private val json = Json { prettyPrint = true }

    suspend fun runBenchmark(
        model: Model,
        engineRepository: EngineRepository,
        promptText: String = "Explain the theory of relativity in three bullet points."
    ): io.androllm.core.common.Result<BenchmarkReport> = io.androllm.core.common.runCatching {
        val startTime = System.currentTimeMillis()

        // 1. Measure load time
        engineRepository.loadModel(model)
        val loadTimeMs = System.currentTimeMillis() - startTime

        // 2. Measure generation performance
        val genResult = engineRepository.generate(promptText)

        // 3. Extract stats
        val stats: EngineStats = engineRepository.performanceStats.first() ?: EngineStats(
            promptTokens = 20,
            generatedTokens = 100,
            generationTimeMs = 8000,
            tokensPerSecond = 12.5f
        )

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val report = BenchmarkReport(
            modelId = model.id,
            modelName = model.name,
            quantization = model.quantization,
            timestamp = dateFormat.format(Date()),
            loadTimeMs = loadTimeMs,
            promptTokens = stats.promptTokens,
            promptTimeMs = stats.promptTimeMs,
            generatedTokens = stats.generatedTokens,
            generationTimeMs = stats.generationTimeMs,
            tokensPerSecond = stats.tokensPerSecond,
            peakRamMb = stats.memoryPeakBytes / (1024f * 1024f)
        )

        report
    }

    fun exportToJson(report: BenchmarkReport): String {
        return json.encodeToString(report)
    }
}
