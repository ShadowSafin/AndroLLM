package io.androllm.core.voice.asr

/**
 * Pluggable streaming speech recognizer (speech-to-text).
 *
 * Implementations emit partial transcripts while the user speaks and signal an
 * endpoint (silence-based turn end) so the voice assistant knows when to send
 * the final transcript to the chat engine.
 *
 * Implementations must be safe to construct lazily; the service calls
 * [ensureInitialized] before the first [feed].
 */
interface SpeechRecognizer {

    /** True once the model is loaded. */
    val isInitialized: Boolean

    /** Loads the model. Returns false when the bundled assets are missing. */
    fun ensureInitialized(): Boolean

    /** Re-applies the endpoint trailing-silence rule from the settings. */
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

    /** Resets the in-flight decoder state. */
    fun reset()

    /** Releases all native resources; the engine must be reinitialized after. */
    fun release()
}