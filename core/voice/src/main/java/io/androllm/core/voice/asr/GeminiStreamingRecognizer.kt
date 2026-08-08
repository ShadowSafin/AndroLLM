package io.androllm.core.voice.asr

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.cloud.voice.GeminiVoiceClient
import io.androllm.core.voice.model.VoiceModels
import io.androllm.core.voice.voicecloud.GeminiApiKeyProvider
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Cloud-backed speech recognizer powered by Google Gemini.
 *
 * The voice assistant always talks to Gemini for speech-to-text — independent
 * of which chat provider the user selected for reasoning. This implementation
 * buffers audio chunks for the current utterance and sends them to Gemini at
 * the endpoint (silence-based turn end), returning the transcript text.
 *
 * The current implementation does not stream partial transcripts; partial
 * feedback would require either a streaming Gemini endpoint or per-chunk
 * calls. The voice service still surfaces interim `Listening…` UI through
 * [io.androllm.core.voice.vad.Vad] until the final transcript arrives.
 */
@Singleton
class GeminiStreamingRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gemini: GeminiVoiceClient,
    private val keyProvider: GeminiApiKeyProvider
) : SpeechRecognizer {

    private var buffer: FloatArray = FloatArray(0)
    private var lastPartial: String = ""
    private var finalText: String = ""

    override val isInitialized: Boolean get() = true

    override fun ensureInitialized(): Boolean = true

    override fun updateSilenceTimeout(seconds: Float) {
        // Endpointing is driven by the voice service's VAD, not the recognizer.
    }

    override fun startSession() {
        buffer = FloatArray(0)
        lastPartial = ""
        finalText = ""
    }

    override fun feed(samples: FloatArray): String {
        // Append to the rolling buffer. The voice service decides when the
        // utterance is finished via VAD; we only report [finalText] then.
        val merged = FloatArray(buffer.size + samples.size)
        System.arraycopy(buffer, 0, merged, 0, buffer.size)
        System.arraycopy(samples, 0, merged, buffer.size, samples.size)
        buffer = merged
        return lastPartial
    }

    override fun isEndpoint(): Boolean = false

    override fun finalText(): String {
        val apiKey = runCatching {
            kotlinx.coroutines.runBlocking { keyProvider.get() }
        }.getOrNull()
        if (apiKey.isNullOrBlank()) {
            Timber.tag("KWS").w("Gemini STT skipped — no API key configured")
            lastPartial = ""
            return ""
        }
        if (buffer.isEmpty()) return ""
        return try {
            val result = kotlinx.coroutines.runBlocking {
                gemini.transcribe(
                    apiKey = apiKey,
                    language = "en",
                    samples = buffer
                )
            }
            finalText = result.text
            lastPartial = result.text
            finalText
        } catch (e: Exception) {
            Timber.tag("KWS").w(e, "Gemini STT failed")
            ""
        } finally {
            buffer = FloatArray(0)
        }
    }

    override fun reset() {
        buffer = FloatArray(0)
        lastPartial = ""
        finalText = ""
    }

    override fun release() {
        reset()
    }

    @Suppress("unused")
    private val sampleRate: Int = VoiceModels.SAMPLE_RATE
}