package io.androllm.core.voice.stt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.core.voice.asr.SpeechRecognitionResult
import io.androllm.core.voice.asr.SpeechRecognitionSession
import io.androllm.core.voice.asr.SpeechRecognizer
import io.androllm.core.whisper.WhisperContext
import io.androllm.core.whisper.WhisperCpuConfig
import io.androllm.core.whisper.WhisperParams
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val TAG = "WhisperSTT"
private const val SAMPLE_RATE = 16000

/** Sliding window length (ms) used for live partial transcripts. */
private const val PARTIAL_WINDOW_MS = 3000L
/** Minimum samples of audio before a partial is attempted. */
private const val PARTIAL_MIN_MS = 600L

/**
 * whisper.cpp speech recognizer.
 *
 * Records the full utterance, then transcribes it offline. When streaming is
 * enabled, live partials are produced by re-transcribing a sliding window of
 * the captured audio (the same approach as the official `whisper-stream`
 * example). The loaded model context is cached and reused across turns.
 */
@Singleton
class WhisperSpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: WhisperModelManager,
    private val settingsStore: VoiceSettingsStore
) : SpeechRecognizer {

    @Volatile private var loadedContext: WhisperContext? = null
    @Volatile private var loadedModelPath: String? = null
    private val loadMutex = Mutex()

    override val engineLabel: String get() = "whisper.cpp"

    override val isInitialized: Boolean get() = loadedContext?.isReady == true

    /** The whisper model file currently loaded (for debug UI). */
    val activeModelFile: File? get() = loadedModelPath?.let { File(it) }

    override suspend fun ensureInitialized(): Boolean {
        loadedContext?.let { if (it.isReady) return true }
        return loadMutex.withLock {
            loadedContext?.let { if (it.isReady) return true }

            val settings = settingsStore.current()
            val file = resolveModelFile(settings.whisperModel)
            if (file == null) {
                Timber.w("$TAG: no whisper model installed")
                return false
            }
            return try {
                val ctx = WhisperContext.fromFile(file.absolutePath)
                loadedContext = ctx
                loadedModelPath = file.absolutePath
                Timber.i("$TAG: loaded ${file.name} (${file.length() / (1024 * 1024)} MB)")
                true
            } catch (t: Throwable) {
                Timber.e(t, "$TAG: model init failed")
                false
            }
        }
    }

    /** Picks the requested model if installed, else any installed model, else null. */
    private fun resolveModelFile(selectedId: String): File? {
        WhisperModels.byId(selectedId)?.let { if (modelManager.isInstalled(it)) return modelManager.modelFile(it) }
        return modelManager.installedModels().firstOrNull()?.let { modelManager.modelFile(it) }
    }

    override suspend fun startSession(
        language: String,
        translate: Boolean,
        numThreads: Int,
        beamSize: Int,
        temperature: Float,
        maxSeconds: Int,
        streamingEnabled: Boolean
    ): SpeechRecognitionSession {
        val ctx = loadedContext ?: throw IllegalStateException("Whisper not initialized")
        val threads = if (numThreads > 0) numThreads else WhisperCpuConfig.preferredThreadCount
        return WhisperSession(
            context = ctx,
            params = WhisperParams(
                language = language,
                translate = translate,
                numThreads = threads,
                beamSize = beamSize,
                temperature = temperature
            ),
            maxSeconds = maxSeconds,
            streamingEnabled = streamingEnabled
        )
    }

    override suspend fun release() {
        runCatching { loadedContext?.let { runCatching { it.release() } } }
        loadedContext = null
        loadedModelPath = null
    }

    /**
     * One recording turn. Audio chunks are appended on the caller thread while
     * transcription (final or partial) runs on whisper's single dispatcher.
     */
    private class WhisperSession(
        private val context: WhisperContext,
        private val params: WhisperParams,
        private val maxSeconds: Int,
        private val streamingEnabled: Boolean
    ) : SpeechRecognitionSession {

        private val lock = Object()
        private var samples = ArrayList<Float>()
        private val startedAt = System.nanoTime()
        private var cancelled = false

        /** True while a sliding-window partial transcription is running. */
        @Volatile var partialRunning: Boolean = false
            private set

        override val elapsedMs: Long get() = (System.nanoTime() - startedAt) / 1_000_000
        override val sampleCount: Int get() = synchronized(lock) { samples.size }

        override fun append(chunk: FloatArray) {
            if (cancelled) return
            synchronized(lock) {
                samples.ensureCapacity(samples.size + chunk.size)
                for (s in chunk) samples.add(s)
            }
        }

        override fun cancel() {
            cancelled = true
            synchronized(lock) { samples = ArrayList() }
        }

        override fun release() {
            cancel()
        }

        override suspend fun partial(): SpeechRecognitionResult {
            if (!streamingEnabled) return emptyResult()
            if (partialRunning) return emptyResult()
            val snapshot = snapshotTail(PARTIAL_WINDOW_MS)
            if (snapshot.size < (SAMPLE_RATE * PARTIAL_MIN_MS / 1000).toInt()) return emptyResult()
            partialRunning = true
            try {
                val t0 = System.nanoTime()
                val transcription = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    context.transcribe(snapshot, params.copy(noContext = true))
                }
                val inferenceMs = (System.nanoTime() - t0) / 1_000_000
                return object : SpeechRecognitionResult {
                    override val text = transcription.text
                    override val confidence: Float? = null
                    override val durationMs = (snapshot.size * 1000L) / SAMPLE_RATE
                    override val inferenceMs = inferenceMs
                    override val language: String? = null
                    override val engine = "whisper.cpp"
                }
            } finally {
                partialRunning = false
            }
        }

        override suspend fun finish(): SpeechRecognitionResult {
            val full = synchronized(lock) { samples.toFloatArray() }
            val trimmed = trimSilence(full)
            if (trimmed.isEmpty()) return emptyResult()
            val t0 = System.nanoTime()
            val transcription = withContext(kotlinx.coroutines.Dispatchers.Default) {
                context.transcribe(trimmed, params)
            }
            val inferenceMs = (System.nanoTime() - t0) / 1_000_000
            val durationMs = (trimmed.size * 1000L) / SAMPLE_RATE
            Timber.i(
                "$TAG: transcribed %.1fs (%d trimmed from %.1fs) in %dms -> '%s'",
                durationMs / 1000f, full.size - trimmed.size, full.size / 16000f,
                inferenceMs, transcription.text.take(80)
            )
            return object : SpeechRecognitionResult {
                override val text = transcription.text
                override val confidence: Float? = null
                override val durationMs = durationMs
                override val inferenceMs = inferenceMs
                override val language: String? = null
                override val engine = "whisper.cpp"
            }
        }

        /**
         * Drops leading/trailing near-silence so whisper decodes the actual
         * utterance (the turn always starts on the wake chime + a pause, and
         * silence padding slows inference and invites hallucinations).
         */
        private fun trimSilence(data: FloatArray): FloatArray {
            val threshold = 0.008f
            var start = 0
            while (start < data.size && kotlin.math.abs(data[start]) < threshold) start++
            var end = data.size
            while (end > start && kotlin.math.abs(data[end - 1]) < threshold) end--
            if (end <= start) return FloatArray(0)
            // Keep a small speech margin at both edges (80 ms).
            start = (start - SAMPLE_RATE / 12).coerceAtLeast(0)
            end = (end + SAMPLE_RATE / 12).coerceAtMost(data.size)
            return data.copyOfRange(start, end)
        }

        private fun emptyResult(): SpeechRecognitionResult = object : SpeechRecognitionResult {
            override val text = ""
            override val confidence: Float? = null
            override val durationMs = 0L
            override val inferenceMs = 0L
            override val language: String? = null
            override val engine = "whisper.cpp"
        }

        /** Last [windowMs] of audio (for sliding-window partials). */
        private fun snapshotTail(windowMs: Long): FloatArray {
            val count = (windowMs * SAMPLE_RATE / 1000).toInt()
            synchronized(lock) {
                val size = samples.size
                val start = (size - count).coerceAtLeast(0)
                val out = FloatArray(size - start)
                for (i in start until size) out[i - start] = samples[i]
                return out
            }
        }
    }
}