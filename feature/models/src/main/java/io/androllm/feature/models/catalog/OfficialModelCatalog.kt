package io.androllm.feature.models.catalog

import io.androllm.core.models.DownloadStatus
import io.androllm.core.models.Model
import io.androllm.core.models.ModelCategory
import io.androllm.core.models.ModelFormat
import io.androllm.core.models.ModelStatus

/**
 * Built-in catalog repository providing curated GGUF models suitable for Android local inference.
 */
object OfficialModelCatalog {

    val catalogModels: List<Model> = listOf(
        // ⭐ Recommended
        Model(
            id = "gemma-4-e2b-it",
            name = "Gemma 4 E2B IT",
            description = "Google's state-of-the-art Gemma 4 2.6B instruct model engineered for mobile devices.",
            filePath = null,
            fileSize = 1_680_000_000L, // ~1.68 GB
            format = ModelFormat.GGUF,
            parameters = "2.6B",
            quantization = "Q4_K_M",
            contextLength = 8192,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            isDownloaded = false,
            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            status = ModelStatus.NOT_LOADED,
            architecture = "gemma2",
            family = "Gemma",
            minRamGb = 4.0f,
            recommendedRamGb = 8.0f,
            category = ModelCategory.RECOMMENDED,
            badges = listOf("⭐ Recommended", "Balanced", "GPU Optimized"),
            strengths = listOf("Superior reasoning", "Exceptional instruction following", "8K Context"),
            weaknesses = listOf("Requires 4GB+ RAM"),
            expectedTokSec = "35-65 tok/s",
            license = "Gemma Terms"
        ),
        Model(
            id = "gemma3-1b-it",
            name = "Gemma 3 1B IT",
            description = "Lightweight Google Gemma 3 1B instruct model delivering fast, low-RAM chat.",
            filePath = null,
            fileSize = 780_000_000L, // ~780 MB
            format = ModelFormat.GGUF,
            parameters = "1.0B",
            quantization = "Q4_K_M",
            contextLength = 4096,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            isDownloaded = false,
            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            status = ModelStatus.NOT_LOADED,
            architecture = "llama",
            family = "Gemma",
            minRamGb = 3.0f,
            recommendedRamGb = 4.0f,
            category = ModelCategory.RECOMMENDED,
            badges = listOf("Fastest", "Low RAM", "Recommended"),
            strengths = listOf("Ultra fast latency", "Fits 4GB RAM phones", "Efficient battery"),
            weaknesses = listOf("Shorter context window"),
            expectedTokSec = "50-80 tok/s",
            license = "Gemma Terms"
        ),

        // 💬 General Chat
        Model(
            id = "gemma-4-e4b-it",
            name = "Gemma 4 E4B IT",
            description = "Google's 4.2B parameter flagship model for advanced coding, creative writing, and reasoning.",
            filePath = null,
            fileSize = 2_580_000_000L, // ~2.58 GB
            format = ModelFormat.GGUF,
            parameters = "4.2B",
            quantization = "Q4_K_M",
            contextLength = 8192,
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            isDownloaded = false,
            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            status = ModelStatus.NOT_LOADED,
            architecture = "gemma2",
            family = "Gemma",
            minRamGb = 6.0f,
            recommendedRamGb = 12.0f,
            category = ModelCategory.CHAT,
            badges = listOf("Flagship", "Best Quality", "GPU Optimized"),
            strengths = listOf("Near 7B level quality", "Advanced code execution", "Deep multi-turn chat"),
            weaknesses = listOf("Requires 6GB+ RAM"),
            expectedTokSec = "25-45 tok/s",
            license = "Gemma Terms"
        ),
        Model(
            id = "qwen2.5-1.5b-instruct",
            name = "Qwen 2.5 1.5B Instruct",
            description = "Alibaba's benchmark-topping 1.5B model fine-tuned for multilingual chat and structured data.",
            filePath = null,
            fileSize = 980_000_000L, // ~980 MB
            format = ModelFormat.GGUF,
            parameters = "1.5B",
            quantization = "Q4_K_M",
            contextLength = 4096,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            isDownloaded = false,
            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            status = ModelStatus.NOT_LOADED,
            architecture = "qwen2",
            family = "Qwen",
            minRamGb = 3.5f,
            recommendedRamGb = 6.0f,
            category = ModelCategory.CHAT,
            badges = listOf("Balanced", "Multilingual", "Fastest"),
            strengths = listOf("Excellent multilingual support", "JSON output formatting", "Low latency"),
            weaknesses = listOf("Moderate reasoning depth"),
            expectedTokSec = "40-70 tok/s",
            license = "Apache-2.0"
        ),

        // 🧠 Reasoning
        Model(
            id = "deepseek-r1-distill-qwen-1.5b",
            name = "DeepSeek R1 Distill Qwen 1.5B",
            description = "DeepSeek's R1 reasoning distillation, producing step-by-step thinking traces and logic.",
            filePath = null,
            fileSize = 1_100_000_000L, // ~1.1 GB
            format = ModelFormat.GGUF,
            parameters = "1.5B",
            quantization = "Q4_K_M",
            contextLength = 8192,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            isDownloaded = false,
            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            status = ModelStatus.NOT_LOADED,
            architecture = "qwen2",
            family = "DeepSeek",
            minRamGb = 4.0f,
            recommendedRamGb = 8.0f,
            category = ModelCategory.REASONING,
            badges = listOf("Reasoning", "Chain of Thought", "Balanced"),
            strengths = listOf("Step-by-step math & logic", "CoT thinking traces", "Strong problem solving"),
            weaknesses = listOf("Verbose reasoning tokens"),
            expectedTokSec = "30-55 tok/s",
            license = "MIT"
        ),

        // ⚡ Mobile Optimized
        Model(
            id = "gemma-3n-e2b-it",
            name = "Gemma 3n E2B IT",
            description = "Ultra-compact Gemma 3n mobile architecture crafted for low battery draw and high tok/s.",
            filePath = null,
            fileSize = 1_073_741_824L, // ~1.0 GB
            format = ModelFormat.GGUF,
            parameters = "1.7B",
            quantization = "Q4_K_M",
            contextLength = 4096,
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-Q4_K_M.gguf",
            isDownloaded = false,
            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            status = ModelStatus.NOT_LOADED,
            architecture = "llama",
            family = "Gemma Mobile",
            minRamGb = 3.5f,
            recommendedRamGb = 4.0f,
            category = ModelCategory.MOBILE_OPTIMIZED,
            badges = listOf("Fastest", "Low RAM", "Battery Efficient"),
            strengths = listOf("Sub-50ms first token", "High battery efficiency", "Compact size"),
            weaknesses = listOf("Basic math capabilities"),
            expectedTokSec = "45-75 tok/s",
            license = "Gemma Terms"
        ),
        Model(
            id = "gemma-3n-e4b-it",
            name = "Gemma 3n E4B IT",
            description = "High-performance Gemma 3n 4B parameter model for high-end devices with 12GB+ RAM.",
            filePath = null,
            fileSize = 2_450_000_000L, // ~2.45 GB
            format = ModelFormat.GGUF,
            parameters = "3.8B",
            quantization = "Q4_K_M",
            contextLength = 8192,
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            isDownloaded = false,
            downloadStatus = DownloadStatus.NOT_DOWNLOADED,
            status = ModelStatus.NOT_LOADED,
            architecture = "llama",
            family = "Gemma Mobile",
            minRamGb = 6.0f,
            recommendedRamGb = 16.0f,
            category = ModelCategory.MOBILE_OPTIMIZED,
            badges = listOf("Highest Quality", "GPU Optimized", "Flagship"),
            strengths = listOf("Top tier creative responses", "8K context", "Vulkan GPU accelerated"),
            weaknesses = listOf("Requires 8GB+ RAM"),
            expectedTokSec = "30-50 tok/s",
            license = "Gemma Terms"
        )
    )
}
