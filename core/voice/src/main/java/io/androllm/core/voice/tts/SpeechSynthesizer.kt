package io.androllm.core.voice.tts

/**
 * Clean abstraction for Speech Synthesis (TTS).
 */
interface SpeechSynthesizer {
    val isInitialized: Boolean
    val sampleRate: Int
    fun ensureInitialized(): Boolean
    fun synthesize(text: String, speed: Float = 1.0f): FloatArray?
    fun release()
}
