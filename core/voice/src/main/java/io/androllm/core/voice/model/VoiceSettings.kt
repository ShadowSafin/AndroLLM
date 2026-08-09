package io.androllm.core.voice.model

/**
 * Persisted configuration of the always-available voice assistant.
 */
data class VoiceSettings(
    /** Master switch for the Voice Assistant. */
    val enabled: Boolean = false,
    /** Enable wake word engine ("Hey Andro"). */
    val enableWakeWord: Boolean = true,
    /** Custom wake phrases (lowercase). "hey andro", "okay andro", "andro" are the defaults. */
    val wakePhrases: List<String> = listOf("hey andro", "okay andro", "andro"),
    /** Keyword-spotter sensitivity (0..1). Higher = more responsive, more false positives. */
    val sensitivity: Float = 0.5f,
    /** Battery saver: reduces CPU (single thread, no continuous conversation). */
    val batterySaver: Boolean = false,
    /** Only run the listener while the device is charging. */
    val chargingOnly: Boolean = false,
    /** Trailing silence (ms) that ends a voice turn (endpoint detection). */
    val silenceTimeoutMs: Int = 2000,
    /** Speech language id ("en"). */
    val language: String = "en",
    /** Automatically detect speech language. */
    val autoLanguageDetection: Boolean = true,
    /** Gemini TTS voice name ("Kore", "Puck", "Fenrir", "Aoede", "Charon"). */
    val ttsVoice: String = "Kore",
    /** TTS speaking speed (0.5..2.0). */
    val speakingSpeed: Float = 1.0f,
    /** TTS pitch (0.5..1.5). */
    val pitch: Float = 1.0f,
    /** Voice playback volume (0.0..1.0). */
    val volume: Float = 1.0f,
    /** Never use cloud services (STT/LLM/TTS). */
    val offlineOnly: Boolean = false,
    /** Keep listening after an answer ends (hands-free conversation). */
    val continuousConversation: Boolean = false,
    /** Speak answers aloud. */
    val autoReadAnswers: Boolean = true,
    /** Fall back to cloud inference when no local model is loaded. */
    val cloudFallback: Boolean = true,
    /** Skip memory retrieval for the fastest possible first token. */
    val lowLatencyMode: Boolean = false,
    /** System noise suppression / echo cancellation on the audio capture. */
    val noiseSuppression: Boolean = false,
    val echoCancellation: Boolean = false,

    // ── Speech recognition (Whisper.cpp) ─────────────────────────────────────

    /** STT engine id ("whisper" = whisper.cpp, "sherpa" = sherpa-onnx ASR). */
    val sttEngine: String = "whisper",
    /** Installed whisper.cpp model id, e.g. "base.en" / "small" / "large-v3-turbo". */
    val whisperModel: String = "base.en",
    /** Whisper language ("auto" = auto-detect) or ISO-639 code like "en". */
    val sttLanguage: String = "auto",
    /** Translate non-English speech to English. */
    val sttTranslate: Boolean = false,
    /** Whisper CPU threads (-1 = auto-detect). */
    val sttThreads: Int = -1,
    /** Beam search size (1 = greedy). */
    val sttBeamSize: Int = 1,
    /** Sampling temperature (0.0 = deterministic). */
    val sttTemperature: Float = 0.0f,
    /** Maximum recording length in seconds per turn. */
    val sttMaxSeconds: Int = 30,
    /** Live partial transcripts while speaking (sliding window). */
    val sttStreaming: Boolean = true,
    /** GPU acceleration (Vulkan) when built in. Reserved; CPU-only for now. */
    val sttGpu: Boolean = false,

    // ── Gemini Live-style overlay experience ──────────────────────────────────

    /** Auto-launch the full-screen floating overlay when the wake word fires. */
    val autoOpenOverlay: Boolean = true,
    /** Play a short chime when the overlay opens / starts listening. */
    val playStartSound: Boolean = true,
    /** Play a short chime when a turn finishes. */
    val playEndSound: Boolean = true,
    /** Overlay glass background opacity (0.25 = very transparent .. 1.0 = solid). */
    val overlayTransparency: Float = 0.78f,
    /** Overlay content scale (0.8 = compact .. 1.2 = large). */
    val overlaySize: Float = 1.0f,
    /** Animation speed multiplier (0.5 = calm .. 2.0 = snappy). */
    val animationSpeed: Float = 1.0f
) {
    companion object {
        const val MIN_SENSITIVITY = 0.1f
        const val MAX_SENSITIVITY = 1.0f
        const val MIN_SILENCE_TIMEOUT_MS = 500
        const val MAX_SILENCE_TIMEOUT_MS = 5000
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 1.5f
        const val MIN_VOLUME = 0.0f
        const val MAX_VOLUME = 1.0f
        const val MIN_OVERLAY_TRANSPARENCY = 0.25f
        const val MAX_OVERLAY_TRANSPARENCY = 1.0f
        const val MIN_OVERLAY_SIZE = 0.8f
        const val MAX_OVERLAY_SIZE = 1.2f
        const val MIN_ANIMATION_SPEED = 0.5f
        const val MAX_ANIMATION_SPEED = 2.0f
        const val MIN_STT_THREADS = 1
        const val MAX_STT_THREADS = 16
        const val MIN_STT_BEAM = 1
        const val MAX_STT_BEAM = 5
        const val MIN_STT_TEMPERATURE = 0.0f
        const val MAX_STT_TEMPERATURE = 1.0f
        const val MIN_STT_MAX_SECONDS = 5
        const val MAX_STT_MAX_SECONDS = 120
    }
}
