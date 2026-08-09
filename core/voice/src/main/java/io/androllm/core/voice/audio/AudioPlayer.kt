package io.androllm.core.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Streaming PCM playback for TTS output.
 *
 * [play] writes the whole buffer at real-time pace and blocks until it has
 * actually been consumed by the hardware (or [stopNow] is called) — the
 * blocking behaviour gives the assistant loop a natural point to check for
 * barge-in. [stopNow] is called from any thread and unblocks [play] quickly.
 *
 * Audio routing: the track is built with [AudioAttributes.USAGE_MEDIA] — the
 * same stream the (audibly working) start/end chimes use.
 * [AudioAttributes.USAGE_ASSISTANT] is deliberately NOT used: on many OEM
 * builds the platform policy mutes ASSISTANT tracks for apps that are not the
 * registered assistant, and while a mic foreground service is active it
 * routes the assistant strategy to the earpiece. An audio focus request
 * (GAIN_TRANSIENT_MAY_DUCK) is made before playback and abandoned after.
 *
 * Pacing: the track buffer is kept small (a few hundred ms) and writes use
 * [AudioTrack.WRITE_NON_BLOCKING], so the write loop naturally paces playback
 * to real time. After the last write we wait for the hardware to drain the
 * tail before stopping — a huge buffer (`samples.size * 2`) plus an immediate
 * `stop()` was silently discarding the ENTIRE answer (writes "succeeded"
 * instantly into the buffer, then stop() dropped it: ~37 s of audio "finished
 * playing" in 2 ms with the speaker silent).
 */
class AudioPlayer(private val context: Context) {

    @Volatile private var track: AudioTrack? = null
    private val playing = AtomicBoolean(false)

    val isPlaying: Boolean get() = playing.get()

    /**
     * Plays [samples] (mono floats) at [sampleRate]. Returns true when the
     * buffer finished playing, false when [stopNow] interrupted playback.
     */
    fun play(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return true
        if (!playing.compareAndSet(false, true)) return true
        return playOnce(samples, sampleRate, AudioAttributes.USAGE_MEDIA)
    }

    /**
     * Single playback attempt with the given usage. Returns true when the
     * whole buffer was actually consumed by the hardware, false when
     * [stopNow] interrupted playback.
     */
    private fun playOnce(samples: FloatArray, sampleRate: Int, usage: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
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
            // A SMALL buffer is essential: it makes WRITE_NON_BLOCKING return
            // 0 when full, pacing the loop to real time. The previous huge
            // buffer swallowed every write instantly and the immediate stop()
            // in the finally block discarded it all.
            .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t

        // Request focus so the platform doesn't duck/mute the answer.
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        val focusResult = runCatching {
            audioManager.requestAudioFocus(focusRequest)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.w("AudioPlayer: focus request not granted (%d) — playing anyway", focusResult)
        }

        // Small pre-roll of silence before the actual audio: the track starts
        // playing the instant play() is called, and on some devices the mixer
        // drops the very first frames while the route comes up. Without it the
        // answer's first word/phoneme can be inaudible (user hears the reply
        // starting several words in).
        val preRollSamples = (sampleRate * 50L / 1000L).toInt()
        val shorts = ShortArray(samples.size + preRollSamples)
        for (i in samples.indices) {
            shorts[i + preRollSamples] = (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }

        var completed = false
        try {
            t.play()
            Timber.i("AudioPlayer: playing %d samples @ %d Hz (focus=%d usage=%d)", samples.size, sampleRate, focusResult, usage)
            // Hard deadline: duration + margin. Guards against a dead mixer
            // looping forever; stopNow (barge-in/cancel) exits much sooner.
            val deadline = SystemClock.elapsedRealtime() +
                (samples.size.toLong() * 1000L) / sampleRate + 3000L

            var offset = 0
            while (offset < shorts.size && playing.get() && SystemClock.elapsedRealtime() < deadline) {
                val written = t.write(
                    shorts, offset, (shorts.size - offset).coerceAtMost(4096),
                    AudioTrack.WRITE_NON_BLOCKING
                )
                when {
                    written > 0 -> offset += written
                    written == 0 -> Thread.sleep(5) // buffer full — mixer is consuming, keep pacing
                    else -> {
                        Timber.w("AudioPlayer: write returned %d at offset %d/%d — stopping", written, offset, shorts.size)
                        break
                    }
                }
            }
            completed = offset >= shorts.size

            if (completed) {
                // Drain the tail: stop() discards whatever is still buffered,
                // so wait until the hardware has consumed every frame. Exits
                // immediately on stopNow via `playing`, or once playState
                // drops out of PLAYING.
                val totalFrames = shorts.size.toLong()
                while (playing.get() &&
                    t.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    t.playbackHeadPosition < totalFrames &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    Thread.sleep(10)
                }
                if (playing.get()) {
                    Timber.i("AudioPlayer: finished playback (%d samples)", samples.size)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "AudioPlayer: playback failed")
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            }
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
