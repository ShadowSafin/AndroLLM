package io.androllm.core.memory.model

/**
 * User-configurable memory system settings. Persisted via a dedicated
 * DataStore so the memory module is self-contained.
 */
data class MemorySettings(
    /** Master switch: when false the whole pipeline is inert. */
    val enabled: Boolean = false,
    /** Minimum cosine similarity before an extracted memory updates an existing one. */
    val similarityThreshold: Float = 0.78f,
    /** Number of memories retrieved per user prompt. */
    val retrievalCount: Int = 5,
    /** Maximum memories injected into the system prompt. */
    val maxContextMemories: Int = 5,
    /** Maximum conversation summaries injected into the system prompt. */
    val maxContextSummaries: Int = 2,
    /** Runs LLM extraction after each assistant response. */
    val extractionEnabled: Boolean = true,
    /** Summarize a conversation every N messages. */
    val summarizationInterval: Int = 20,
    /** Absolute path of the GGUF embedding model ("" = not configured). */
    val embeddingModelPath: String = "",
    /**
     * Embedding model id routed through the active cloud provider
     * (e.g. "openai/text-embedding-3-small"). When set AND a provider is
     * configured, cloud embeddings are preferred over the local GGUF model.
     */
    val cloudEmbeddingModel: String = "",
    /** Native embedding context length. */
    val embeddingContextLength: Int = 512,
    /** Native embedding batch size. */
    val embeddingBatchSize: Int = 512,
    /** Prefix prepended to queries (BGE models: "query:"). */
    val queryPrefix: String = "",
    /** Prefix prepended to stored passages (BGE models: "passage:"). */
    val passagePrefix: String = "",
    /** Keeps the embedding model resident in memory after the first embed. */
    val keepEmbeddingModelLoaded: Boolean = true
) {
    companion object {
        const val THRESHOLD_MIN = 0.5f
        const val THRESHOLD_MAX = 0.99f
        const val RETRIEVAL_MIN = 1
        const val RETRIEVAL_MAX = 20
        const val SUMMARIZATION_MIN = 4
        const val SUMMARIZATION_MAX = 100
    }
}
