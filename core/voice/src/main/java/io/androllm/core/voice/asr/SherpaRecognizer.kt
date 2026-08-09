package io.androllm.core.voice.asr

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sherpa Streaming Recognizer implementation of [SpeechRecognizer].
 * Streaming Zipformer ASR on ONNX runtime mobile.
 */
@Singleton
class SherpaRecognizer @Inject constructor(
    private val recognizer: SherpaOnnxStreamingRecognizer
) : StreamingSpeechRecognizer {

    override val isInitialized: Boolean
        get() = recognizer.isInitialized

    override fun ensureInitialized(): Boolean {
        return recognizer.ensureInitialized()
    }

    override fun updateSilenceTimeout(seconds: Float) {
        recognizer.updateSilenceTimeout(seconds)
    }

    override fun startSession() {
        recognizer.startSession()
    }

    override fun feed(samples: FloatArray): String {
        return recognizer.feed(samples)
    }

    override fun isEndpoint(): Boolean {
        return recognizer.isEndpoint()
    }

    override fun finalText(): String {
        return recognizer.finalText()
    }

    override fun lastTokenCount(): Int {
        return recognizer.lastTokenCount()
    }

    override fun estimatedConfidence(): Float {
        return recognizer.estimatedConfidence()
    }

    override fun reset() {
        recognizer.reset()
    }

    override fun release() {
        recognizer.release()
    }
}
