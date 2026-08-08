package io.androllm.core.voice.wakeword

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.voice.model.VoiceModels
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * sherpa-onnx keyword-spotter backend for [WakeWordEngine].
 *
 * Runs the quantized zipformer2 KWS model continuously on the microphone
 * stream. It is the only thing active while the assistant sleeps, so it must
 * stay cheap — the model is int8 and decodes a single stream on one thread.
 */
@Singleton
class SherpaOnnxWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : WakeWordEngine {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    override val isInitialized: Boolean get() = spotter != null

    @Synchronized
    override fun ensureInitialized(): Boolean {
        if (spotter != null) return true
        val hasAssets = hasAsset(VoiceModels.KWS_ENCODER) &&
            hasAsset(VoiceModels.KWS_TOKENS) &&
            hasAsset(VoiceModels.KWS_KEYWORDS)
        if (!hasAssets) {
            Timber.w("SherpaOnnxWakeWordEngine: KWS assets missing")
            return false
        }
        return runCatching {
            val config = KeywordSpotterConfig().apply {
                featConfig = FeatureConfig().apply {
                    sampleRate = VoiceModels.SAMPLE_RATE
                    featureDim = VoiceModels.FEATURE_DIM
                }
                modelConfig = OnlineModelConfig().apply {
                    transducer = OnlineTransducerModelConfig().apply {
                        encoder = VoiceModels.KWS_ENCODER
                        decoder = VoiceModels.KWS_DECODER
                        joiner = VoiceModels.KWS_JOINER
                    }
                    tokens = VoiceModels.KWS_TOKENS
                    numThreads = 1
                    debug = false
                    provider = "cpu"
                    modelType = "zipformer2"
                }
                keywordsFile = VoiceModels.KWS_KEYWORDS
                keywordsScore = 1.0f
                keywordsThreshold = 0.0001f
                numTrailingBlanks = 1
            }
            spotter = KeywordSpotter(context.assets, config)
            true
        }.onFailure { Timber.e(it, "KWS init failed") }.getOrDefault(false)
    }

    override fun startSession(phrases: List<String>) {
        val s = spotter ?: return
        runCatching { stream?.release() }
        // Create default stream to monitor all configured keywords in keywords.txt
        val st = s.createStream()
        Timber.tag("KWS").i("startSession: listening with keywords.txt defaults")
        stream = st
    }

    override fun feed(samples: FloatArray): String? {
        val s = spotter ?: return null
        val st = stream ?: return null
        return runCatching {
            st.acceptWaveform(samples, VoiceModels.SAMPLE_RATE)
            var decodes = 0
            while (s.isReady(st)) {
                s.decode(st)
                decodes++
            }
            val result = s.getResult(st)
            val rawKw = result.keyword
            val tokensList = result.tokens.toList()
            val tokensStr = tokensList.joinToString(" ")
            if (decodes > 0 || rawKw.isNotBlank() || tokensStr.isNotBlank()) {
                Timber.tag("KWS").i(
                    "feed: samples=${samples.size} decodes=$decodes kw='$rawKw' tokens=[$tokensStr]"
                )
            }
            val hasTokens = tokensList.isNotEmpty()
            val matchesTokenPhonemes = tokensList.any { t ->
                t.contains("HH") || t.contains("EY") || t.contains("AE") || t.contains("OW") || t.contains("ANDR")
            }
            val detected = rawKw.isNotBlank() || (hasTokens && matchesTokenPhonemes)
            val keyword = if (detected) rawKw.ifBlank { "HEY ANDRO" } else null
            if (keyword != null) {
                Timber.tag("KWS").i("DETECTED: $keyword (rawKw='$rawKw', tokens=[$tokensStr])")
                s.reset(st)
            }
            keyword
        }.onFailure { Timber.e(it, "KWS feed failed") }.getOrNull()
    }

    override fun reset() {
        runCatching { stream?.let { spotter?.reset(it) } }
    }

    @Synchronized
    override fun release() {
        runCatching { stream?.release() }
        stream = null
        runCatching { spotter?.release() }
        spotter = null
    }

    private fun hasAsset(path: String): Boolean =
        runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
}