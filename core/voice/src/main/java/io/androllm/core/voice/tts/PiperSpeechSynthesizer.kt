package io.androllm.core.voice.tts

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Piper TTS Speech Synthesizer implementation of [SpeechSynthesizer] and [OfflineTtsEngine].
 * Powered by ONNX Runtime VITS / Piper TTS models.
 */
@Singleton
class PiperSpeechSynthesizer @Inject constructor(
    private val engine: SherpaOnnxOfflineTtsEngine
) : SpeechSynthesizer, OfflineTtsEngine {

    override val isInitialized: Boolean
        get() = engine.isInitialized

    override val sampleRate: Int
        get() = engine.sampleRate

    override fun ensureInitialized(): Boolean {
        return engine.ensureInitialized()
    }

    override fun synthesize(text: String, speed: Float): FloatArray? {
        return engine.synthesize(text, speed)
    }

    override fun release() {
        engine.release()
    }
}
