package io.androllm.core.voice.wakeword

import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenWakeWord engine implementation of [WakeWordEngine].
 * Runs lightweight ONNX wake word models continuously on micro-audio chunks.
 */
@Singleton
class OpenWakeWordEngine @Inject constructor(
    private val engine: SherpaOnnxWakeWordEngine
) : WakeWordEngine {

    override val isInitialized: Boolean
        get() = engine.isInitialized

    override fun ensureInitialized(): Boolean {
        return engine.ensureInitialized()
    }

    override fun startSession(phrases: List<String>) {
        engine.startSession(phrases)
    }

    override fun feed(samples: FloatArray): String? {
        return engine.feed(samples)
    }

    override fun reset() {
        engine.reset()
    }

    override fun release() {
        engine.release()
    }
}
