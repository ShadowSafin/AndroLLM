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

    /** Execution provider override: adb shell setprop debug.androllm.provider nnapi */
    private val inferenceProvider: String
        get() = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            if (m.invoke(null, "debug.androllm.provider") == "nnapi") "nnapi" else "cpu"
        }.getOrDefault("cpu")

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
                    // Diagnostic toggle: adb shell setprop debug.androllm.provider nnapi
                    // bypasses ORT's CPU kernels (buggy on Oryon/SM8845 + Android 16,
                    // producing garbage inference). Default: cpu.
                    provider = inferenceProvider
                    modelType = "zipformer2"
                }
                // Robustness for real microphone speech: a strong per-token
                // boost keeps the keyword path alive in beam search (the
                // official demo default is 1.5), and a wider beam reduces the
                // chance the 7-phoneme keyword path gets pruned mid-phrase.
                maxActivePaths = 10
                keywordsFile = VoiceModels.KWS_KEYWORDS
                keywordsScore = 2.0f
                keywordsThreshold = 0.0001f
                numTrailingBlanks = 1
            }
            spotter = KeywordSpotter(context.assets, config)
            // Diagnostic (Step 1): log the loaded model + the wake phrases
            // actually configured in the bundled keywords.txt.
            val phrases = readAssetLines(VoiceModels.KWS_KEYWORDS)
                .filter { it.isNotBlank() }
                .mapNotNull { line -> line.substringAfter('@', "").takeIf { it.isNotBlank() } }
                .distinct()
            Timber.tag("KWS").i(
                "KWS model loaded: encoder=%s decoder=%s joiner=%s tokens=%s keywords=%s | phrases=%s | " +
                    "sampleRate=%d featureDim=%d score=%.1f threshold=%.4f trailingBlanks=%d",
                VoiceModels.KWS_ENCODER, VoiceModels.KWS_DECODER, VoiceModels.KWS_JOINER,
                VoiceModels.KWS_TOKENS, VoiceModels.KWS_KEYWORDS, phrases,
                VoiceModels.SAMPLE_RATE, VoiceModels.FEATURE_DIM,
                config.keywordsScore, config.keywordsThreshold, config.numTrailingBlanks
            )
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
            // Diagnostic (Step 3): log EVERY inference call so a device test
            // shows whether audio reaches the model and whether decodes run.
            Timber.tag("KWS").i(
                "feed: samples=${samples.size} decodes=$decodes kw='$rawKw' tokens=[$tokensStr]"
            )
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

    private fun readAssetLines(path: String): List<String> =
        runCatching { context.assets.open(path).bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())
}