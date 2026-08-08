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
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true
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
    }
}
