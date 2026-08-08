package io.androllm.core.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming PCM playback for TTS output.
 *
 * [play] blocks until the whole buffer is written or [stopNow] is called —
 * the blocking behaviour gives the assistant loop a natural point to check
 * for barge-in between sentences. [stopNow] is called from any thread and
 * unblocks [play] immediately (interruption support, like Gemini Live).
 */
class AudioPlayer {

    private var track: AudioTrack? = null
    private val playing = AtomicBoolean(false)

    val isPlaying: Boolean get() = playing.get()

    /**
     * Plays [samples] (mono floats) at [sampleRate]. Returns true when the
     * buffer finished playing, false when [stopNow] interrupted playback.
     */
    fun play(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return true
        if (!playing.compareAndSet(false, true)) return true

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer.coerceAtLeast(samples.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t

        val shorts = ShortArray(samples.size)
        for (i in samples.indices) {
            shorts[i] = (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }

        var completed = false
        try {
            t.play()
            var offset = 0
            while (offset < shorts.size && playing.get()) {
                val written = t.write(shorts, offset, (shorts.size - offset).coerceAtMost(4096))
                if (written <= 0) break
                offset += written
            }
            completed = offset >= shorts.size
        } catch (_: Exception) {
            // Playback failure is non-fatal — the voice loop moves on.
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
            track = null
            playing.set(false)
        }
        return completed
    }

    /** Cancels playback instantly (barge-in / stop). */
    fun stopNow() {
        playing.set(false)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    fun release() {
        stopNow()
    }
}
