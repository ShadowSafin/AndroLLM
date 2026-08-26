package io.androllm.docs

/**
 * In-code documentation for the AndroLLM project.
 * Phase 1 establishes the architecture; Phase 2 implements the LLM engine.
 */
object AppDocs {

    /**
     * Application metadata.
     */
    object App {
        const val NAME = "AndroLLM"
        const val TAGLINE = "Private AI. Offline. On your device."
        const val DESCRIPTION = "Run powerful AI models locally on Android."
        const val VERSION = "1.0.0"
        const val VERSION_CODE = 1
    }

    /**
     * Architecture overview.
     */
    object Architecture {
        const val LAYERS = "app, core (common, ui, database, datastore, navigation, models, network, utils), feature (home, chat, models, settings, splash), engine, docs"
        const val PATTERNS = "MVVM + Clean Architecture + Repository Pattern"
        const val DI = "Hilt"
        const val NAVIGATION = "Navigation Compose"
        const val DATABASE = "Room"
        const val PREFERENCES = "DataStore"
        const val NETWORKING = "Ktor Client"
        const val IMAGE_LOADING = "Coil"
    }

    /**
     * Phase 2 roadmap notes.
     */
    object Roadmap {
        const val INFERENCE_ENGINE = "Implement LiteRT-LM engine in the engine module"
        const val MODEL_DOWNLOADS = "Implement streaming model downloads via DownloadManager"
        const val MODEL_CATALOG = "Connect ModelApi to a real remote catalog"
        const val GPU_ACCELERATION = "Detect and use GPU acceleration for inference"
        const val TOOL_CALLING = "Add tool/function calling support when engines support it"
    }

    /**
     * Cloud experience layer (added on top of the LiteLLM gateway; the local
     * engine and core architecture are untouched). See
     * documentation/cloud/cloud-pipeline.md for the full guide.
     */
    object Cloud {
        const val PIPELINE =
            "User request -> prompt assembly -> validation -> cache lookup -> tool planning -> " +
                "provider selection (fallback chain) -> cloud request -> result observation -> " +
                "tool result handling -> final answer -> usage logging"
        const val TOOL_CALLING =
            "Native OpenAI-compatible tools array plus CloudFallbackToolParser for providers without " +
                "tool-call syntax; argument validation, confirmation gates for sensitive actions, and " +
                "conditional multi-step workflows via CloudToolRouter + CloudConditionals"
        const val USAGE_DASHBOARD =
            "CloudUsageMeter records every request (tokens, estimated cost, latency, cache behavior, " +
                "tool calls); dashboard in feature:cloud shows overview, tokens, cost, latency, provider " +
                "health, tool calling, cache performance, alerts, filters, CSV export, history"
        const val PROMPT_CACHING =
            "PromptCache fingerprints stable prefixes (system prompts + tool schemas, never private " +
                "content); CloudCacheHints decorates requests provider-aware (cache_control for " +
                "Anthropic-family, byte-stable prefixes for automatic prefix caching); invalidation " +
                "and savings are tracked"
        const val RELIABILITY =
            "Every stage is failure-isolated: provider fallback before the first token, retry policy on " +
                "408/429/5xx, corruption-safe persistence, usage accounting that never throws into the " +
                "request path"
    }
}
