// JNI bridge between the AndroLLM Kotlin layer and the vendored whisper.cpp
// library. Follows the official whisper.android example (whisper-jni.c) and the
// whisper.h C API.
//
// Native method names target the Kotlin file `WhisperLib.kt` (top-level external
// functions compile to the class `WhisperLibKt`):
//   io.androllm.core.whisper.WhisperLibKt
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Asset loader (stream the .bin model straight from the APK assets)
// ---------------------------------------------------------------------------

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env, jobject assetManager, const char *asset_path) {
    LOGI("Loading model from asset '%s'", asset_path);
    AAssetManager *manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'", asset_path);
        return NULL;
    }

    struct whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close,
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperInitFromAsset(
        JNIEnv *env, jclass clazz, jobject assetManager, jstring asset_path_str) {
    UNUSED(clazz);
    const char *path = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    struct whisper_context *ctx = whisper_init_from_asset(env, assetManager, path);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, path);
    return (jlong) ctx;
}

JNIEXPORT jlong JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperInitFromFile(
        JNIEnv *env, jclass clazz, jstring model_path_str) {
    UNUSED(clazz);
    const char *path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("Loading model from file '%s'", path);
    struct whisper_context *ctx =
            whisper_init_from_file_with_params(path, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperFree(
        JNIEnv *env, jclass clazz, jlong ctx_ptr) {
    UNUSED(env); UNUSED(clazz);
    whisper_free((struct whisper_context *) ctx_ptr);
}

// ---------------------------------------------------------------------------
// Transcription
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperFull(
        JNIEnv *env, jclass clazz,
        jlong ctx_ptr, jfloatArray audio, jstring language,
        jboolean translate, jint num_threads, jint beam_size,
        jfloat temperature, jboolean no_context) {
    UNUSED(clazz);
    struct whisper_context *ctx = (struct whisper_context *) ctx_ptr;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    const jsize sample_count = (*env)->GetArrayLength(env, audio);

    struct whisper_full_params params = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);

    const char *lang = NULL;
    char lang_buf[16] = {0};
    if (language != NULL) {
        const char *lang_java = (*env)->GetStringUTFChars(env, language, NULL);
        if (lang_java != NULL && strcmp(lang_java, "auto") != 0) {
            snprintf(lang_buf, sizeof(lang_buf), "%s", lang_java);
            lang = lang_buf; // empty buf stays NULL -> auto-detect
        }
        (*env)->ReleaseStringUTFChars(env, language, lang_java);
    }

    params.language = lang;                 // NULL/empty -> auto-detect
    params.translate = translate ? 1 : 0;
    params.n_threads = num_threads;
    params.no_context = no_context ? 1 : 0;
    params.temperature = temperature;
    params.temperature_inc = 0.0f;          // deterministic decoding
    params.print_realtime = false;
    params.print_progress = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.offset_ms = 0;
    params.single_segment = false;
    if (beam_size > 1) {
        params.beam_search.beam_size = beam_size;
    }

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, samples, sample_count);
    if (rc != 0) {
        LOGW("whisper_full failed with code %d", rc);
    } else {
        whisper_print_timings(ctx);
    }
    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperSegmentCount(
        JNIEnv *env, jclass clazz, jlong ctx_ptr) {
    UNUSED(env); UNUSED(clazz);
    return whisper_full_n_segments((struct whisper_context *) ctx_ptr);
}

JNIEXPORT jstring JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperSegmentText(
        JNIEnv *env, jclass clazz, jlong ctx_ptr, jint index) {
    UNUSED(clazz);
    const char *text = whisper_full_get_segment_text((struct whisper_context *) ctx_ptr, index);
    return (*env)->NewStringUTF(env, text ? text : "");
}

JNIEXPORT jlong JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperSegmentT0(
        JNIEnv *env, jclass clazz, jlong ctx_ptr, jint index) {
    UNUSED(env); UNUSED(clazz);
    return whisper_full_get_segment_t0((struct whisper_context *) ctx_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperSegmentT1(
        JNIEnv *env, jclass clazz, jlong ctx_ptr, jint index) {
    UNUSED(env); UNUSED(clazz);
    return whisper_full_get_segment_t1((struct whisper_context *) ctx_ptr, index);
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperSystemInfo(JNIEnv *env, jclass clazz) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperBenchMemcpy(JNIEnv *env, jclass clazz, jint threads) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_bench_memcpy_str(threads));
}

JNIEXPORT jstring JNICALL
Java_io_androllm_core_whisper_WhisperLibKt_whisperBenchGgmlMulMat(JNIEnv *env, jclass clazz, jint threads) {
    UNUSED(clazz);
    return (*env)->NewStringUTF(env, whisper_bench_ggml_mul_mat_str(threads));
}
