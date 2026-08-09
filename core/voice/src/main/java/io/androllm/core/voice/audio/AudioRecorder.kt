package io.androllm.core.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
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

    /** Actual sample rate the device granted (query after [start] succeeds). */
    @Volatile var actualSampleRate: Int = sampleRate
        private set

    /** Actual capture buffer size in bytes (query after [start] succeeds). */
    @Volatile var bufferSizeBytes: Int = 0
        private set

    /** Capture source in use (MediaRecorder.AudioSource.*). */
    @Volatile var source: Int = MediaRecorder.AudioSource.MIC
        private set

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

        // Default = the device's plain/default microphone (AudioSource.MIC) —
        // exactly what the official sherpa-onnx Android example feeds into the
        // recognizer and what streaming ASR models are most accurate on. When
        // the user explicitly enables system noise suppression / echo
        // cancellation, capture switches to VOICE_RECOGNITION (the system DSP
        // does the NS/EC); that source is aggressive on some OEM builds and can
        // mangle consonants, so if it fails to open we fall back to MIC.
        val source = if (noiseSuppression || echoCancellation) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val bufferSize = minBuffer.coerceAtLeast(chunkSamples * 2)
        var selectedSource = source
        var record = createRecord(selectedSource, bufferSize)
        if (record == null && selectedSource != MediaRecorder.AudioSource.MIC) {
            android.util.Log.w(
                "AudioRecorder",
                "VOICE_RECOGNITION unavailable — falling back to the default MIC source"
            )
            selectedSource = MediaRecorder.AudioSource.MIC
            record = createRecord(selectedSource, bufferSize)
        }
        if (record == null) return false
        // Diagnostic: confirm the device actually honors the requested rate.
        android.util.Log.i(
            "AudioRecorder",
            String.format(
                "AudioRecord ready: requested %dHz mono PCM16 | actual sampleRate=%d | source=%s",
                sampleRate, record.sampleRate, sourceLabel(selectedSource)
            )
        )
        audioRecord = record
        this.source = selectedSource
        this.actualSampleRate = record.sampleRate
        this.bufferSizeBytes = bufferSize
        recording.set(true)

        recordThread = Thread({
            val buf = ShortArray(chunkSamples)
            val floatBuf = FloatArray(chunkSamples)
            record.startRecording()
            val startedAt = SystemClock.elapsedRealtime()
            android.util.Log.i(
                "AudioRecorder",
                String.format(
                    "Recording started: %dHz MONO PCM16 | source=%s | buffer=%dB (%dms) | chunk=%d samples",
                    record.sampleRate, sourceLabel(selectedSource),
                    bufferSize, bufferSize * 1000 / (record.sampleRate * 2),
                    chunkSamples
                )
            )
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
                android.util.Log.i(
                    "AudioRecorder",
                    String.format(
                        "Recording stopped after %dms (capture thread ended)",
                        SystemClock.elapsedRealtime() - startedAt
                    )
                )
            }
        }, "voice-capture")
        recordThread?.isDaemon = true
        recordThread?.start()
        return true
    }

    private fun sourceLabel(source: Int): String =
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION" else "MIC"

    /**
     * Builds an [AudioRecord] for [source]. Returns null when the source cannot
     * be opened (missing permission / unsupported source) so the caller can
     * fall back to the default [MediaRecorder.AudioSource.MIC].
     */
    private fun createRecord(source: Int, bufferSize: Int): AudioRecord? =
        runCatching {
            val r = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release()
                null
            }
        }.getOrNull()

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
