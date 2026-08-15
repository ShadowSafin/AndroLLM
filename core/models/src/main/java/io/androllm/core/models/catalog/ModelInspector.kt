package io.androllm.core.models.catalog

import io.androllm.core.models.gguf.GgufReader
import io.androllm.core.models.gguf.GgufType
import java.io.File

/**
 * Automatic discovery of a downloaded model's real properties — never trusts
 * the filename. Uses the pure-JVM GGUF reader ([GgufReader]) to parse
 * architecture, quantization, parameters, tokenizer, chat template, tensor
 * index and size straight from the file bytes.
 */
class ModelInspector {

    data class Inspection(
        val format: String,
        val architecture: String,
        val family: String,
        val parameters: String?,
        val quantization: String,
        val quantLevel: QuantLevel,
        val sizeBytes: Long,
        val contextLength: Int,
        val embeddingLength: Int,
        val blockCount: Int,
        val headCount: Int,
        val headCountKv: Int,
        val tokenizerModel: String?,
        val chatTemplate: String?,
        val tensorCount: Int,
        val tensorLayout: String,
        val streamable: Boolean,
        val supportedBackends: List<RuntimeBackend>,
        val isMoe: Boolean,
        val expertCount: Int,
        val sharedExperts: Int,
        val modelStreamType: ModelStreamType,
        val denseOrMoe: DenseOrMoe,
    )

    /**
     * Inspects [file]. Returns a failure (never throws) when the file is not
     * a parseable GGUF model or its architecture is unsupported.
     */
    fun inspect(file: File): Result<Inspection> = runCatching {
        val parsed = GgufReader.parse(file)
        val arch = parsed.architecture
        val isSupportedArch = SupportedArchitectures.isSupported(arch)

        val dominantType = dominantTensorType(parsed.tensors)
        val quantization = dominantType?.label ?: "UNKNOWN"

        val isMoe = parsed.int("$arch.expert_count") != null && (parsed.int("$arch.expert_count") ?: 0) > 0
        val expertCount = (parsed.int("$arch.expert_count") ?: 0).toInt()
        val sharedExperts = (parsed.int("$arch.expert_used_count") ?: 0).toInt()

        Inspection(
            format = "GGUF",
            architecture = arch,
            family = architectureFamily(arch),
            parameters = parsed.kv["general.size_label"] ?: estimateParameters(parsed),
            quantization = quantization,
            quantLevel = QuantClassifier.classify(quantization),
            sizeBytes = parsed.fileSize,
            contextLength = (parsed.int("$arch.context_length") ?: 4096L).toInt(),
            embeddingLength = (parsed.int("$arch.embedding_length") ?: 0L).toInt(),
            blockCount = (parsed.int("$arch.block_count") ?: 0L).toInt(),
            headCount = (parsed.int("$arch.attention.head_count") ?: 0L).toInt(),
            headCountKv = (parsed.int("$arch.attention.head_count_kv") ?: 0L).toInt(),
            tokenizerModel = parsed.kv["tokenizer.ggml.model"],
            chatTemplate = parsed.kv["tokenizer.chat_template"],
            tensorCount = parsed.tensors.size,
            tensorLayout = "BLOCKED",
            streamable = isSupportedArch,
            supportedBackends = listOf(RuntimeBackend.CPU, RuntimeBackend.VULKAN),
            isMoe = isMoe,
            expertCount = expertCount,
            sharedExperts = sharedExperts,
            denseOrMoe = if (isMoe) DenseOrMoe.MOE else DenseOrMoe.DENSE,
            modelStreamType = if (isMoe) ModelStreamType.STREAMING_MOE else ModelStreamType.STREAMING_DENSE,
        )
    }

    /**
     * Dominant weight type across layer tensors (the model's quantization).
     * Prefers attention/FFN block weights over small embeddings/output.
     */
    private fun dominantTensorType(tensors: List<io.androllm.core.models.gguf.GgufTensor>): GgufType? {
        val counts = HashMap<GgufType, Int>()
        for (t in tensors) {
            val type = t.type ?: continue
            if (t.name.startsWith("blk.")) counts[type] = (counts[type] ?: 0) + 1
        }
        val dominant = counts.maxByOrNull { it.value }?.key
        if (dominant != null) return dominant
        return tensors.firstOrNull()?.type
    }

    /** Fallback parameter estimate: ~12 x layers x hidden squared (Llama-style dense). */
    private fun estimateParameters(parsed: io.androllm.core.models.gguf.GgufRead): String? {
        val arch = parsed.architecture
        val layers = parsed.int("$arch.block_count") ?: 0
        val hidden = parsed.int("$arch.embedding_length") ?: 0
        if (layers <= 0 || hidden <= 0) return null
        val params = 12L * layers * hidden * hidden
        return if (params >= 1_000_000_000L) {
            "%.1fB".format(params / 1_000_000_000.0)
        } else {
            "%.0fM".format(params / 1_000_000.0)
        }
    }

    /**
     * Human family label for a GGUF architecture id (e.g. "qwen2" -> "Qwen").
     * Unknown architectures fall back to the capitalized id.
     */
    private fun architectureFamily(arch: String): String {
        val match = FAMILY_PREFIXES.firstOrNull { (prefix, _) -> arch.startsWith(prefix) }
        return match?.second ?: arch.replaceFirstChar { it.uppercaseChar() }
    }

    private companion object {
        /** Longest prefix wins; ordering matters. */
        val FAMILY_PREFIXES = listOf(
            "qwen2vl" to "Qwen", "qwen2moe" to "Qwen", "qwen3moe" to "Qwen", "qwen3next" to "Qwen",
            "qwen3vl" to "Qwen", "qwen35moe" to "Qwen", "qwen35" to "Qwen", "qwen2" to "Qwen",
            "qwen3" to "Qwen", "qwen" to "Qwen",
            "llama-embed" to "Llama", "llama4" to "Llama", "llama" to "Llama",
            "gemma4-assistant" to "Gemma", "gemma4" to "Gemma", "gemma3n" to "Gemma", "gemma3" to "Gemma",
            "gemma2" to "Gemma", "gemma-embedding" to "Gemma", "gemma" to "Gemma",
            "deepseek32" to "DeepSeek", "deepseek2-ocr" to "DeepSeek", "deepseek4" to "DeepSeek",
            "deepseek2" to "DeepSeek", "deepseek" to "DeepSeek",
            "mistral4" to "Mistral", "mistral3" to "Mistral", "mistral" to "Mistral",
            "phi3" to "Phi", "phi2" to "Phi", "phimoe" to "Phi",
            "falcon-h1" to "Falcon", "falcon3" to "Falcon", "falcon" to "Falcon",
            "minicpm3" to "MiniCPM", "minicpm" to "MiniCPM",
            "command-r" to "Cohere", "cohere2moe" to "Cohere", "cohere2" to "Cohere",
            "glm4moe" to "GLM", "glm4" to "GLM", "glm-dsa" to "GLM",
            "internlm2" to "InternLM", "gpt-oss" to "GPT-OSS", "gptneox" to "GPT-NeoX",
            "gpt2" to "GPT", "gptj" to "GPT-J", "olmo2" to "OLMo", "olmoe" to "OLMo",
            "olmo" to "OLMo", "starcoder2" to "StarCoder", "starcoder" to "StarCoder",
            "stablelm" to "StableLM", "nemotron_h_moe" to "Nemotron", "nemotron_h" to "Nemotron",
            "nemotron" to "Nemotron", "exaone-moe" to "EXAONE", "exaone4" to "EXAONE",
            "exaone" to "EXAONE", "hunyuan-moe" to "Hunyuan", "hunyuan_dense" to "Hunyuan",
            "granitemoe" to "Granite", "granitehybrid" to "Granite", "granite" to "Granite",
            "mamba2" to "Mamba", "mamba" to "Mamba", "rwkv6qwen2" to "RWKV", "rwkv7" to "RWKV",
            "rwkv6" to "RWKV", "baichuan" to "Baichuan", "bloom" to "BLOOM", "bitnet" to "BitNet",
            "bert" to "BERT", "t5encoder" to "T5", "t5" to "T5", "jais2" to "Jais", "jais" to "Jais",
            "chatglm" to "ChatGLM", "grok" to "Grok", "dbrx" to "DBRX", "jamba" to "Jamba",
            "arctic" to "Arctic", "openelm" to "OpenELM", "codeshell" to "CodeShell",
            "orion" to "Orion", "xverse" to "XVERSE", "plamo3" to "PLaMo", "plamo2" to "PLaMo",
            "plamo" to "PLaMo", "chameleon" to "Chameleon", "smollm3" to "SmolLM",
            "arcee" to "Arcee", "afmoe" to "AFM", "laguna" to "Laguna", "mpt" to "MPT",
            "eurobert" to "EuroBERT", "jina-bert-v3" to "Jina-BERT", "jina-bert-v2" to "Jina-BERT",
            "nomic-bert-moe" to "Nomic-BERT", "nomic-bert" to "Nomic-BERT", "modern-bert" to "ModernBERT",
            "neo-bert" to "NeoBERT", "rnd1" to "RND1", "kimi-linear" to "Kimi", "step35" to "Step",
        )
    }
}
