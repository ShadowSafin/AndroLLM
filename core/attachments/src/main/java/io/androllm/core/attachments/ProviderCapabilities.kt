package io.androllm.core.attachments

/**
 * Best-effort capability detection for the active model. The LiteLLM proxy
 * does not expose a capability manifest, so support is inferred from the
 * model identifier (e.g. "openai/gpt-4o", "anthropic/claude-3-5-sonnet").
 * Local LiteRT/llama.cpp runtimes are not addressable here at all — the chat
 * layer passes NO model id for them, so every capability resolves to false.
 *
 * Capability flags (the extensibility contract):
 *
 * - [supportsAttachments] — cloud models: true (the pipeline parses files
 *   on-device and sends extracted text / native image parts). Local models:
 *   false — no parsing, no OCR, no uploads, no UI.
 * - [supportsVision] — known multimodal models accept native image parts.
 * - [supportsStreaming] — every cloud provider streams tokens.
 * - [supportsToolCalling] — every cloud provider receives native `tools`.
 * - [supportsReasoning] / [supportsImageGeneration] — reserved for future
 *   provider manifests.
 *
 * The rules are conservative: an unknown model is treated as NOT
 * vision-capable so images fall back to OCR text — we never fake vision by
 * sending image parts to a model that cannot see them. A future local runtime
 * opts into attachments simply by making this function return true for its
 * model id — no UI or backend change required.
 */
object ProviderCapabilities {

    /** Model ids that are known to accept image content parts. */
    private val VISION_MARKERS = listOf(
        "gpt-4o", "gpt-4.1", "gpt-4-turbo", "gpt-4-vision", "gpt-4.5", "gpt-5",
        "o1", "o3", "o4",
        "gemini", "gemma-3", "gemma3",
        "claude-3", "claude-3.5", "claude-3.7", "claude-4", "claude-sonnet", "claude-opus",
        "llama-3.2", "llama-4", "llama4", "llama-3.2-vision",
        "qwen-vl", "qwen2.5-vl", "qwen3-vl", "qwen2-vl",
        "pixtral", "llava", "internvl", "glm-4v", "molmo", "phi-3.5-vision", "phi-4",
        "grok-2-vision", "grok-4", "idefics", "fuyu", "paligemma", "smolvlm", "mini-cpm-v"
    )

    /** Model ids that accept PDFs / file uploads natively. */
    private val FILE_UPLOAD_MARKERS = listOf(
        "gpt-4o", "gpt-4.1", "gpt-5", "o1", "o3", "o4",
        "gemini-1.5", "gemini-2", "gemini-2.5",
        "claude-3", "claude-3.5", "claude-3.7", "claude-4",
        "qwen-vl", "qwen2.5-vl"
    )

    /**
     * True when the active model can use the Chat Attachment pipeline.
     *
     * Cloud models: true — the pipeline extracts text/OCR locally and the
     * cloud provider receives conversation + extracted content + message.
     * Local models: false — the chat layer passes no cloud model id (null or
     * blank), so this resolves to false without any provider-name checks.
     */
    fun supportsAttachments(modelId: String?): Boolean =
        !modelId.isNullOrBlank()

    /** Every cloud provider streams tokens incrementally. */
    fun supportsStreaming(modelId: String?): Boolean =
        !modelId.isNullOrBlank()

    /** Every cloud provider receives the OpenAI-compatible `tools` array. */
    fun supportsToolCalling(modelId: String?): Boolean =
        !modelId.isNullOrBlank()

    /** True when [modelId] (lowercased) contains a known vision marker. */
    fun supportsVision(modelId: String?): Boolean {
        val id = modelId?.lowercase().orEmpty()
        if (id.isBlank()) return false
        return VISION_MARKERS.any { id.contains(it) }
    }

    /** True when [modelId] is known to accept native file uploads. */
    fun supportsFileUploads(modelId: String?): Boolean {
        val id = modelId?.lowercase().orEmpty()
        if (id.isBlank()) return false
        return FILE_UPLOAD_MARKERS.any { id.contains(it) }
    }

    /** True when the provider id is a known multimodal vendor. */
    fun isKnownMultimodalProvider(providerId: String?): Boolean {
        val id = providerId?.lowercase().orEmpty()
        return id.contains("openai") || id.contains("anthropic") || id.contains("gemini") ||
            id.contains("google") || id.contains("mistral") || id.contains("xai") ||
            id.contains("groq") || id.contains("together") || id.contains("openrouter")
    }
}
