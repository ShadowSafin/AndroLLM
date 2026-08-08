package io.androllm.core.voice.vad

/**
 * Lightweight energy-based voice activity detector used for barge-in: while
 * the assistant is speaking, the mic keeps running and this detector decides
 * whether the user started talking again. When it flips to speech, the
 * assistant stops TTS, cancels generation and listens again.
 *
 * A hangover keeps the "speaking" state stable across short pauses so one
 * utterance is not cut into many.
 */
class Vad(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES
) {

    private var speechActive = false
    private var quietFrames = 0

    val isSpeech: Boolean get() = speechActive

    /**
     * Feeds one audio chunk and returns the current voice-activity state.
     * A single loud chunk flips the state on; a run of quiet chunks flips it
     * off (hangover).
     */
    fun process(samples: FloatArray): Boolean {
        var energy = 0.0
        for (s in samples) energy += s.toDouble() * s
        energy /= samples.size.coerceAtLeast(1)

        val speaking = energy > threshold
        if (speaking) {
            speechActive = true
            quietFrames = 0
        } else if (speechActive) {
            quietFrames++
            if (quietFrames > hangoverFrames) {
                speechActive = false
                quietFrames = 0
            }
        }
        return speechActive
    }

    fun reset() {
        speechActive = false
        quietFrames = 0
    }

    companion object {
        /** RMS energy above this counts as speech (calibrated for 16 kHz float audio). */
        const val DEFAULT_THRESHOLD = 0.005f

        /** ~0.8 s of quiet (200 ms chunks) before the turn is considered over. */
        const val DEFAULT_HANGOVER_FRAMES = 4
    }
}
