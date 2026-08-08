package io.androllm.core.voice.wakeword

/**
 * Pluggable wake-word engine.
 *
 * One concrete implementation runs at a time. Implementations must:
 *  - be 100% offline (the spec mandates no cloud dependency for the wake word);
 *  - consume ~200 ms float chunks at 16 kHz mono via [feed];
 *  - return the triggered keyword name (e.g. `"HEY_ANDROID"`) when fired, or
 *    `null` while still listening;
 *  - support [startSession] for resetting per turn (e.g. when the barge-in
 *    refires the wake word).
 *
 * Backends:
 *  - [SherpaOnnxWakeWordEngine] — sherpa-onnx KWS zipformer2 phoneme model.
 *  - future: OpenWakeWord TFLite, Picovoice Porcupine, custom on-device ASR.
 */
interface WakeWordEngine {

    /** True once the engine has loaded its model and is ready to [feed]. */
    val isInitialized: Boolean

    /** Loads the model. Returns false when the bundled assets are missing. */
    fun ensureInitialized(): Boolean

    /**
     * Opens a new detection session for the given phrases.
     *
     * Implementations may use [phrases] for keyword expansion when their vocab
     * supports it, or ignore them and rely on a baked-in keyword file. The
     * default contract is: file-based keywords are authoritative; runtime
     * phrases are best-effort and fall back silently when out-of-vocab.
     */
    fun startSession(phrases: List<String>)

    /**
     * Feeds one ~200 ms float chunk sampled at 16 kHz mono.
     * Returns the triggered keyword name, or `null` while still listening.
     */
    fun feed(samples: FloatArray): String?

    /** Resets the in-flight decoder state (called after a successful hit). */
    fun reset()

    /** Releases all native resources; the engine must be reinitialized after. */
    fun release()
}