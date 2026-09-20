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
        // ---- LiteRT container facts (req §11; empty/zero for GGUF inspections) ----
        /** .litertlm container format version (0 when not a .litertlm file). */
        val containerVersion: Int = 0,
        /** Expected LlmModelType identifier (catalog expectation, "" when unknown). */
        val containerType: String = "",
        /** True when the container magic + format mark it loadable by LiteRT. */
        val litertCompatible: Boolean = false,
        /** Why it is (in)compatible — surfaced in UI and validation reports. */
        val compatibilityNote: String = "",
        /** Tokenizer location: "embedded" (.litertlm), "sidecar" (.tflite + tokenizer.model), "unknown". */
        val tokenizerLocation: String = "unknown",
        /** Special tokens observed or expected (stop sequences, eos/bos markers). */
        val specialTokens: List<String> = emptyList(),
    )

    /**
     * Inspects any downloaded artifact (req §11): routes `.litertlm`/`.tflite`
     * files to the LiteRT container inspector and everything else to the GGUF
     * reader. [catalog] supplies the expected engine/architecture/quantization
     * context the container bytes alone cannot provide.
     */
    fun inspectAny(file: File, catalog: CatalogModel? = null): Result<Inspection> {
        val lower = file.name.lowercase()
        return if (lower.endsWith(".litertlm") || lower.endsWith(".tflite")) {
            inspectLiteRt(file, catalog)
        } else {
            inspect(file)
        }
    }

    /**
     * Inspects a LiteRT artifact: container magic, format version, byte size,
     * engine/architecture/quantization (from [catalog] when provided, else
     * filename heuristics), context length, tokenizer location, parameter
     * count, special tokens and LiteRT compatibility.
     */
    fun inspectLiteRt(file: File, catalog: CatalogModel? = null): Result<Inspection> = runCatching {
        if (!file.exists()) throw IllegalArgumentException("File not found: ${file.absolutePath}")
        if (!file.canRead()) throw IllegalArgumentException("File not readable: ${file.absolutePath}")
        val size = file.length()
        if (size < 12) throw IllegalArgumentException("File too small to be a model artifact (${size} bytes)")

        val header = ByteArray(12)
        file.inputStream().buffered().use { input ->
            var off = 0
            while (off < 12) {
                val read = input.read(header, off, 12 - off)
                if (read < 0) break
                off += read
            }
            if (off < 12) throw IllegalArgumentException("File too small to be a model artifact")
        }

        val litertlmMagic = byteArrayOf('L'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(),
            'R'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte())
        val tfliteId = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())
        val isLitertlm = header.copyOfRange(0, 8).contentEquals(litertlmMagic)
        val isTflite = header.copyOfRange(4, 8).contentEquals(tfliteId)
        if (!isLitertlm && !isTflite) {
            throw IllegalArgumentException(
                "Not a LiteRT model: expected a .litertlm container (magic \"LITERTLM\") " +
                    "or a .tflite flatbuffer (file id \"TFL3\")")
        }

        val version = if (isLitertlm) {
            (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8) or
                ((header[10].toInt() and 0xFF) shl 16) or ((header[11].toInt() and 0xFF) shl 24)
        } else 0
        if (isLitertlm && version < 1) {
            throw IllegalArgumentException("Unsupported .litertlm container version $version")
        }

        val architecture = catalog?.architecture?.ifBlank { null }
            ?: guessArchitecture(file.nameWithoutExtension)
        val family = catalog?.family?.ifBlank { null } ?: guessFamily(architecture)
        val quantization = catalog?.quantization?.ifBlank { null }
            ?: guessQuantization(file.nameWithoutExtension)
        val contextLength = catalog?.contextLength?.takeIf { it > 0 } ?: 4096
        val containerType = catalog?.containerType.orEmpty()
        val tokenizerLocation = if (isLitertlm) "embedded" else {
            val sidecar = File(file.parentFile, "tokenizer.model")
            if (sidecar.exists() && sidecar.length() > 0) "sidecar" else "missing-sidecar"
        }
        val compatible = if (isTflite) tokenizerLocation == "sidecar" else true
        val note = when {
            isLitertlm -> ".litertlm container v$version, loadable by the LiteRT-LM chat engine" +
                if (containerType.isNotBlank()) " (type $containerType)" else ""
            tokenizerLocation == "sidecar" -> ".tflite flatbuffer with sidecar tokenizer.model present"
            else -> ".tflite flatbuffer — tokenizer.model sidecar not found beside the file"
        }
        Inspection(
            format = if (isLitertlm) "LITERTLM" else "TFLITE",
            architecture = architecture,
            family = family,
            parameters = catalog?.parameters?.ifBlank { null },
            quantization = quantization,
            quantLevel = QuantClassifier.classify(quantization),
            sizeBytes = size,
            contextLength = contextLength,
            embeddingLength = 0,
            blockCount = 0,
            headCount = 0,
            headCountKv = 0,
            tokenizerModel = if (isLitertlm) "embedded" else if (tokenizerLocation == "sidecar") "sidecar" else null,
            chatTemplate = catalog?.chatTemplate,
            tensorCount = 0,
            tensorLayout = catalog?.tensorLayout?.ifBlank { null } ?: "BLOCKED",
            streamable = catalog?.streamable ?: true,
            supportedBackends = catalog?.backendValues?.ifEmpty { null }
                ?: listOf(RuntimeBackend.CPU, RuntimeBackend.VULKAN),
            isMoe = catalog?.denseOrMoeValue == DenseOrMoe.MOE,
            expertCount = catalog?.expertCount ?: 0,
            sharedExperts = catalog?.sharedExperts ?: 0,
            modelStreamType = catalog?.modelStreamTypeValue ?: ModelStreamType.STREAMING_DENSE,
            denseOrMoe = catalog?.denseOrMoeValue ?: DenseOrMoe.DENSE,
            containerVersion = version,
            containerType = containerType,
            litertCompatible = compatible,
            compatibilityNote = note,
            tokenizerLocation = tokenizerLocation,
            specialTokens = catalog?.stopSequences ?: emptyList(),
        )
    }

    /** Architecture guess from a file stem (e.g. "Qwen3_1.7B" -> "qwen3"). */
    private fun guessArchitecture(stem: String): String {
        val lower = stem.lowercase()
        val candidates = SupportedArchitectures.ALL.sortedByDescending { it.length }
        return candidates.firstOrNull { lower.contains(it.replace(".", "").replace("-", "")) }
            ?: candidates.firstOrNull { lower.contains(it) } ?: "unknown"
    }

    private fun guessFamily(arch: String): String {
        val spec = ModelMetadataRegistry.familyForArchitecture(arch)
        return spec?.displayName ?: arch.replaceFirstChar { it.uppercaseChar() }
    }

    /** Quantization guess from a file stem (e.g. "…_q8_…" -> "Q8"). */
    private fun guessQuantization(stem: String): String {
        val upper = stem.uppercase()
        return when {
            "_Q8" in upper || "-Q8" in upper || upper.endsWith("Q8") -> "Q8"
            "_Q4" in upper || "-Q4" in upper || "INT4" in upper -> "Q4"
            "Q4" in upper -> "Q4"
            "INT8" in upper -> "INT8"
            "FP32" in upper || "F32" in upper -> "FP32"
            "MIXED" in upper -> "MIXED"
            else -> "UNKNOWN"
        }
    }

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
            litertCompatible = false,
            compatibilityNote = "GGUF container — not loadable by the LiteRT runtime " +
                "(install the .litertlm build of this model)",
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
