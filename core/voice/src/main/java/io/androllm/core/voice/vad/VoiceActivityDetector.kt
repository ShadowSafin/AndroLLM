package io.androllm.core.voice.vad

/**
 * Clean abstraction for Voice Activity Detection (VAD).
 */
interface VoiceActivityDetector {
    val isSpeech: Boolean
    fun process(samples: FloatArray): Boolean
    fun reset()
}
