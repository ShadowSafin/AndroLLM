package io.androllm.core.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.androllm.core.voice.model.VoiceModels
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/**
 * Continuous microphone capture at 16 kHz mono.
 *
 * A dedicated thread reads PCM frames and pushes ~200 ms float chunks onto
 * [chunks]. Consumers (wake word → ASR → VAD) pull from the same channel so a
 * single capture stream feeds every stage of the assistant, exactly like a
 * real-time voice agent.
 *
 * Capture never runs on the main thread. All sherpa engines accept the float
 * chunks directly via `acceptWaveform(float[], 16000)`.
 */
class AudioRecorder(
    private val sampleRate: Int = VoiceModels.SAMPLE_RATE,
    private val chunkSamples: Int = 3200, // 200 ms @ 16 kHz
    private val noiseSuppression: Boolean = true,
    private val echoCancellation: Boolean = true
) {

    private val _chunks = Channel<FloatArray>(Channel.UNLIMITED)
    val chunks: Channel<FloatArray> = _chunks

    private val recording = AtomicBoolean(false)
    @Volatile private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    val isRecording: Boolean get() = recording.get()

    /**
     * Starts the capture loop. Returns false when the microphone is not
     * available (permission missing / no mic).
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording.get()) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        // VOICE_RECOGNITION hands noise suppression + echo cancellation to the
        // system DSP; plain MIC is used when the user disabled both.
        val source = if (noiseSuppression || echoCancellation) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val record = AudioRecord(
            source,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer.coerceAtLeast(chunkSamples * 2)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        recording.set(true)

        recordThread = Thread({
            val buf = ShortArray(chunkSamples)
            val floatBuf = FloatArray(chunkSamples)
            record.startRecording()
            try {
                while (recording.get() && !Thread.currentThread().isInterrupted) {
                    val read = record.read(buf, 0, buf.size)
                    if (read < 0) break // error code — stop instead of busy-looping
                    if (read == 0) {
                        Thread.sleep(5)
                        continue
                    }
                    for (i in 0 until read) {
                        floatBuf[i] = buf[i] / 32768.0f
                    }
                    if (read < chunkSamples) {
                        val chunk = FloatArray(read)
                        System.arraycopy(floatBuf, 0, chunk, 0, read)
                        _chunks.trySend(chunk)
                    } else {
                        _chunks.trySend(floatBuf.clone())
                    }
                }
            } catch (_: Exception) {
                // Capture loop ends on stop() — nothing to surface here.
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                audioRecord = null
            }
        }, "voice-capture")
        recordThread?.isDaemon = true
        recordThread?.start()
        return true
    }

    fun stop() {
        if (!recording.compareAndSet(true, false)) return
        recordThread?.interrupt()
        recordThread = null
        _chunks.close()
    }

    fun release() {
        stop()
    }
}
