package io.androllm.core.voice.asr

/**
 * Legacy sample-by-sample streaming recognizer contract (kept for the
 * Sherpa-ONNX and Gemini recognizers which feed audio incrementally).
 *
 * The active voice-assistant pipeline uses the offline [SpeechRecognizer]
 * contract instead; this interface exists so the streaming implementations
 * continue to compile and can be reused independently.
 */
interface StreamingSpeechRecognizer {

    /** True once the model is loaded. */
    val isInitialized: Boolean

    /** Loads the model. Returns false when the bundled assets are missing. */
    fun ensureInitialized(): Boolean

    /** Re-applies the endpoint trailing-silence rule. */
    fun updateSilenceTimeout(seconds: Float)

    /** Starts a fresh recognition turn. */
    fun startSession()

    /**
     * Feeds one ~200 ms float chunk sampled at 16 kHz mono and returns the
     * current partial transcript (or "" when nothing recognized yet).
     */
    fun feed(samples: FloatArray): String

    /** True when the recognizer considers the current utterance finished. */
    fun isEndpoint(): Boolean

    /** Final transcript for the completed turn. */
    fun finalText(): String

    /** Diagnostic: number of tokens decoded in the current session. */
    fun lastTokenCount(): Int = 0

    /** Diagnostic: heuristic decoder confidence (0..1) for the current result. */
    fun estimatedConfidence(): Float = 0f

    /** Resets the in-flight decoder state. */
    fun reset()

    /** Releases all native resources; the engine must be reinitialized after. */
    fun release()
}