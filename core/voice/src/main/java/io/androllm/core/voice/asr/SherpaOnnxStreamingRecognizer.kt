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
) : SpeechRecognizer {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

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
                    provider = "cpu"
                    modelType = "zipformer"
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