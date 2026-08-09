package io.androllm.feature.voice.service

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Short system chimes for the voice overlay — a soft "start listening" blip
 * and a higher "turn finished" chime. Uses [ToneGenerator] (no assets needed)
 * so the overlay never depends on TTS state or bundled models.
 */
object VoiceSounds {

    private val handler = Handler(Looper.getMainLooper())

    /** Played when the overlay opens / starts listening. */
    fun playStart() {
        play(tone = ToneGenerator.TONE_PROP_ACK, durationMs = 140, volume = 55)
    }

    /** Played when a turn finishes. */
    fun playEnd() {
        play(tone = ToneGenerator.TONE_PROP_NACK, durationMs = 180, volume = 60)
    }

    private fun play(tone: Int, durationMs: Int, volume: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            generator.startTone(tone, durationMs)
            // ToneGenerator must be released after the tone finishes; a short
            // grace period keeps the tail from clipping.
            handler.postDelayed({ runCatching { generator.release() } }, durationMs + 80L)
        }
    }
}
