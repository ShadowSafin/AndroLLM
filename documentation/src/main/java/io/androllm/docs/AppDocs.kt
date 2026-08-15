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
}
