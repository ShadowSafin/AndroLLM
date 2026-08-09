package io.androllm.core.voice.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.voice.model.VoiceModels
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * sherpa-onnx streaming recognizer backend for [SpeechRecognizer].
 *
 * 100% offline. The recognizer emits partial transcripts while the user
 * speaks and flags an endpoint after a configurable trailing silence, which
 * the assistant uses as "the user finished the turn".
 */
@Singleton
class SherpaOnnxStreamingRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) : StreamingSpeechRecognizer {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    /** Execution provider override: adb shell setprop debug.androllm.provider nnapi */
    private val inferenceProvider: String
        get() = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            if (m.invoke(null, "debug.androllm.provider") == "nnapi") "nnapi" else "cpu"
        }.getOrDefault("cpu")

    override val isInitialized: Boolean get() = recognizer != null

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (recognizer != null) return true
        val hasAssets = hasAsset(VoiceModels.ASR_ENCODER) && hasAsset(VoiceModels.ASR_TOKENS)
        if (!hasAssets) {
            Timber.w("SherpaOnnxStreamingRecognizer: ASR assets missing")
            return false
        }
        return runCatching {
            val config = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig().apply {
                    sampleRate = VoiceModels.SAMPLE_RATE
                    featureDim = VoiceModels.FEATURE_DIM
                }
                modelConfig = OnlineModelConfig().apply {
                    transducer = OnlineTransducerModelConfig().apply {
                        encoder = VoiceModels.ASR_ENCODER
                        decoder = VoiceModels.ASR_DECODER
                        joiner = VoiceModels.ASR_JOINER
                    }
                    tokens = VoiceModels.ASR_TOKENS
                    numThreads = 2
                    debug = false
                    // Diagnostic toggle: adb shell setprop debug.androllm.provider nnapi
                    // bypasses ORT's CPU kernels (buggy on Oryon/SM8845 + Android 16).
                    provider = inferenceProvider
                    // sherpa-onnx-streaming-zipformer-en-2023-06-26 (zipformer2,
                    // chunk-16-left-128) â€” the streaming model the official
                    // sherpa-onnx Android example uses by default. Substantially
                    // more accurate than the older 20M zipformer it replaced.
                    // NOTE: this is a zipformer2 graph; the app ships the ORT
                    // 1.28.0 override (jniLibs) that fixes the zipformer2
                    // encoder miscomputation on Oryon SoCs (k2-fsa/sherpa-onnx#3845).
                    modelType = "zipformer2"
                }
                enableEndpoint = true
                endpointConfig = EndpointConfig().apply {
                    rule1 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = DEFAULT_TRAILING_SILENCE_SECONDS,
                        minUtteranceLength = 0.3f
                    )
                    rule2 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = 1.0f,
                        minUtteranceLength = 3.0f
                    )
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 2.0f,
                        minUtteranceLength = 5.0f
                    )
                }
            }
            recognizer = OnlineRecognizer(context.assets, config)
            // Step 2/6/7: log the exact decoding configuration the model runs with.
            Timber.tag("ASR").i(
                "Recognizer initialized: model=%s | tokens=%s | %dHz %d-dim fbank | " +
                    "provider=%s threads=%d | modelType=%s | endpoint(rule1: %s)",
                VoiceModels.ASR_ENCODER, VoiceModels.ASR_TOKENS,
                config.featConfig.sampleRate, config.featConfig.featureDim,
                config.modelConfig.provider, config.modelConfig.numThreads,
                config.modelConfig.modelType,
                "trailingSilence=${config.endpointConfig.rule1.minTrailingSilence}s " +
                    "minUtterance=${config.endpointConfig.rule1.minUtteranceLength}s " +
                    "mustContainNonSilence=${config.endpointConfig.rule1.mustContainNonSilence}"
            )
            true
        }.onFailure { Timber.e(it, "ASR init failed") }.getOrDefault(false)
    }

    @Synchronized
    override fun updateSilenceTimeout(seconds: Float) {
        val r = recognizer ?: return
        r.config.endpointConfig.rule1.minTrailingSilence = seconds
    }

    override fun startSession() {
        val r = recognizer ?: return
        runCatching { stream?.release() }
        stream = r.createStream("")
    }

    override fun feed(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val st = stream ?: return ""
        return runCatching {
            st.acceptWaveform(samples, VoiceModels.SAMPLE_RATE)
            while (r.isReady(st)) {
                r.decode(st)
            }
            r.getResult(st).text
        }.onFailure { Timber.e(it, "ASR feed failed") }.getOrDefault("")
    }

    override fun isEndpoint(): Boolean {
        val r = recognizer ?: return false
        val st = stream ?: return false
        return runCatching { r.isEndpoint(st) }.getOrDefault(false)
    }

    override fun finalText(): String {
        val r = recognizer ?: return ""
        val st = stream ?: return ""
        return runCatching { r.getResult(st).text }.getOrDefault("")
    }

    override fun lastTokenCount(): Int {
        val r = recognizer ?: return 0
        val st = stream ?: return 0
        return runCatching { r.getResult(st).tokens.size }.getOrDefault(0)
    }

    /**
     * Heuristic confidence: the mean joiner softmax value the decoder assigned
     * across all emitted tokens of the current session. The streaming API has
     * no single confidence field; a strong, confident hypothesis shows
     * consistently high joiner probabilities, while noise-induced hypotheses
     * are lower and noisier. Logged for Step 2 diagnostics only.
     */
    override fun estimatedConfidence(): Float {
        val r = recognizer ?: return 0f
        val st = stream ?: return 0f
        return runCatching {
            val probs = r.getResult(st).ysProbs
            if (probs.isEmpty()) 0f else (probs.average().toFloat()).coerceIn(0f, 1f)
        }.getOrDefault(0f)
    }

    override fun reset() {
        runCatching { stream?.release() }
        stream = null
    }

    @Synchronized
    override fun release() {
        reset()
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun hasAsset(path: String): Boolean =
        runCatching { context.assets.open(path).close(); true }.getOrDefault(false)

    companion object {
        private const val DEFAULT_TRAILING_SILENCE_SECONDS = 2.0f
    }
}