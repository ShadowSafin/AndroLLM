package io.androllm.core.voice.tts

import io.androllm.core.cloud.voice.GeminiVoiceClient
import io.androllm.core.voice.voicecloud.GeminiApiKeyProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Cloud-backed text-to-speech powered by Google Gemini.
 *
 * The voice assistant always uses Gemini TTS — independent of the chat
 * provider. The voice pipeline is intentionally provider-agnostic on the
 * LLM side: even when the user routes chat through LiteLLM, Claude, or
 * local GGUF, the spoken response is still synthesized by Gemini.
 *
 * The model loads lazily: the assistant spends most of its life listening
 * for the wake word, and we want to avoid paying for TTS until the first
 * reply is needed.
 */
@Singleton
class GeminiOfflineTtsEngine @Inject constructor(
    private val gemini: GeminiVoiceClient,
    private val keyProvider: GeminiApiKeyProvider
) : OfflineTtsEngine {

    override val isInitialized: Boolean get() = true

    override fun ensureInitialized(): Boolean = true

    override val sampleRate: Int
        get() = lastSampleRate ?: 24_000

    private var lastSampleRate: Int? = null

    override fun synthesize(text: String, speed: Float): FloatArray? {
        if (text.isBlank()) return null
        val apiKey = runCatching { runBlocking { keyProvider.get() } }.getOrNull()
        if (apiKey.isNullOrBlank()) {
            Timber.tag("KWS").w("Gemini TTS skipped — no API key configured")
            return null
        }
        return runCatching {
            runBlocking {
                val result = gemini.synthesize(apiKey = apiKey, text = text)
                lastSampleRate = result.sampleRate
                result.samples
            }
        }.onFailure { Timber.tag("KWS").w(it, "Gemini TTS failed") }
            .getOrNull()
    }

    override fun release() {
        // The Gemini client is a shared singleton; nothing to release.
    }
}