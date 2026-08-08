package io.androllm.core.voice.tts

/**
 * Pluggable text-to-speech engine.
 *
 * The voice assistant calls [synthesize] once per complete sentence so the
 * assistant can start speaking while the model is still generating the rest
 * (sentence-based streaming TTS). The TTS layer must NOT block on network;
 * backends that hit the cloud should do so asynchronously and surface
 * [synthesize] as a suspend function (see future implementations).
 *
 * Today only an offline backend is bound; future Android-TTS / Gemini-TTS /
 * Piper backends can replace the implementation without changes to the
 * service or settings.
 */
interface OfflineTtsEngine {

    /** True once the model is loaded. */
    val isInitialized: Boolean

    /** Loads the model. Returns false when the bundled assets are missing. */
    fun ensureInitialized(): Boolean

    /** PCM sample rate of synthesized audio (e.g. 22050). */
    val sampleRate: Int

    /**
     * Synthesizes [text] into a mono float PCM buffer.
     * [speed] is the speaking rate (0.5..2.0). Returns null on failure.
     */
    fun synthesize(text: String, speed: Float): FloatArray?

    /** Releases all native resources; the engine must be reinitialized after. */
    fun release()
}