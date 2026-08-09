package io.androllm.core.whisper

import android.content.res.AssetManager
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** One transcribed segment with millisecond timestamps. */
data class WhisperSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/** Full transcription of one audio buffer. */
data class WhisperTranscription(
    val segments: List<WhisperSegment>
) {
    /** Plain transcript (segment texts joined with a space). */
    val text: String
        get() = segments.joinToString(" ") { it.text.trim() }.trim()
}

/** Tuning knobs for a [WhisperContext.transcribe] call. */
data class WhisperParams(
    /** "auto" = auto-detect, otherwise an ISO-639-1/2 code such as "en". */
    val language: String = "auto",
    /** Translate (non-English speech) to English. */
    val translate: Boolean = false,
    val numThreads: Int = WhisperCpuConfig.preferredThreadCount,
    /** 1 = greedy, >1 = beam search with this beam size. */
    val beamSize: Int = 1,
    /** Sampling temperature (0.0 = deterministic). */
    val temperature: Float = 0.0f,
    /** Run each buffer independently (no cross-call context reuse). */
    val noContext: Boolean = false
)

/**
 * Thread-safe handle to a loaded whisper.cpp context.
 *
 * whisper.cpp forbids using one context from multiple threads, so every native
 * call is serialized onto a dedicated single-thread dispatcher. All
 * transcription happens off the calling thread.
 */
class WhisperContext private constructor(private var ptr: Long) {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)

    val isReady: Boolean get() = ptr != 0L

    /** Transcribes [samples] (16 kHz mono float, -1..1) and returns segments. */
    suspend fun transcribe(
        samples: FloatArray,
        params: WhisperParams = WhisperParams()
    ): WhisperTranscription = withContext(dispatcher) {
        require(isReady) { "whisper context released" }
        val rc = whisperFull(
            ptr, samples,
            params.language, params.translate,
            params.numThreads, params.beamSize,
            params.temperature, params.noContext
        )
        if (rc != 0) error("whisper_full failed (rc=$rc)")
        val count = whisperSegmentCount(ptr)
        val segments = ArrayList<WhisperSegment>(count)
        for (i in 0 until count) {
            segments += WhisperSegment(
                startMs = whisperSegmentT0(ptr, i) * 10,
                endMs = whisperSegmentT1(ptr, i) * 10,
                text = whisperSegmentText(ptr, i)
            )
        }
        WhisperTranscription(segments)
    }

    suspend fun systemInfo(): String = withContext(dispatcher) {
        require(isReady) { "whisper context released" }
        whisperSystemInfo()
    }

    suspend fun release() = withContext(dispatcher) {
        if (ptr != 0L) {
            whisperFree(ptr)
            ptr = 0L
        }
    }

    protected fun finalize() {
        runCatching { whisperFree(ptr) }
    }

    companion object {
        /** Loads a ggml model file from the filesystem. */
        fun fromFile(path: String): WhisperContext {
            check(WhisperNative.loaded) { "libwhisper.so not loaded" }
            val p = whisperInitFromFile(path)
            check(p != 0L) { "Failed to load whisper model from $path" }
            return WhisperContext(p)
        }

        /** Loads a ggml model directly from the APK assets. */
        fun fromAsset(assetManager: AssetManager, path: String): WhisperContext {
            check(WhisperNative.loaded) { "libwhisper.so not loaded" }
            val p = whisperInitFromAsset(assetManager, path)
            check(p != 0L) { "Failed to load whisper model from asset $path" }
            return WhisperContext(p)
        }
    }
}
