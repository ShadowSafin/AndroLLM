package io.androllm.core.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log

private const val TAG = "WhisperLib"

/**
 * Loads the bundled `libwhisper.so` (whisper.cpp + ggml compiled for arm64-v8a).
 * Safe to call from any thread; loading is idempotent.
 */
object WhisperNative {
    val loaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("whisper")
            Log.i(TAG, "libwhisper.so loaded (abi=${Build.SUPPORTED_ABIS.firstOrNull()})")
            true
        }.getOrElse {
            Log.e(TAG, "Failed to load libwhisper.so", it)
            false
        }
    }
}

// Kotlin top-level `external fun`s compile to WhisperLibKt; the JNI symbols in
// jni.c match `io.androllm.core.whisper.WhisperLibKt_*`.
external fun whisperInitFromAsset(assetManager: AssetManager, path: String): Long
external fun whisperInitFromFile(path: String): Long
external fun whisperFree(ptr: Long)

/** Runs full transcription. Returns 0 on success, non-zero on failure. */
external fun whisperFull(
    ptr: Long,
    audio: FloatArray,
    language: String,
    translate: Boolean,
    numThreads: Int,
    beamSize: Int,
    temperature: Float,
    noContext: Boolean
): Int

external fun whisperSegmentCount(ptr: Long): Int
external fun whisperSegmentText(ptr: Long, index: Int): String
external fun whisperSegmentT0(ptr: Long, index: Int): Long
external fun whisperSegmentT1(ptr: Long, index: Int): Long
external fun whisperSystemInfo(): String
external fun whisperBenchMemcpy(threads: Int): String
external fun whisperBenchGgmlMulMat(threads: Int): String
