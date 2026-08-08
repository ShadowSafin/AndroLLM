package io.androllm.core.voice.vad

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sherpa-ONNX Voice Activity Detector (VAD) implementation of [VoiceActivityDetector].
 * Uses energy and Silero VAD heuristics for endpoint & speech detection.
 */
@Singleton
class SherpaVad @Inject constructor() : VoiceActivityDetector {

    private val vad = Vad()

    override val isSpeech: Boolean
        get() = vad.isSpeech

    override fun process(samples: FloatArray): Boolean {
        return vad.process(samples)
    }

    override fun reset() {
        vad.reset()
    }
}
