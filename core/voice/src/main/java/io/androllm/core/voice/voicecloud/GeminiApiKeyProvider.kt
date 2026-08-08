package io.androllm.core.voice.voicecloud

import io.androllm.core.cloud.security.KeyCipher
import io.androllm.core.common.getOrNull
import io.androllm.core.database.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Resolves the user's Gemini API key for the voice assistant.
 *
 * The key lives encrypted in the Room `settings` table as
 * [io.androllm.core.models.AppSettings.geminiApiKeyEncrypted] and is decrypted
 * on demand via [KeyCipher]. The voice assistant never stores the key in
 * memory beyond the duration of a single request.
 *
 * The voice pipeline is independent of the chat provider: even when the user
 * routes chat through LiteLLM, Claude, or local GGUF, the voice assistant
 * still needs a Gemini key for STT and TTS. The two are configured in
 * separate screens (voice settings vs cloud providers) so each is
 * opt-in.
 */
@Singleton
class GeminiApiKeyProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val keyCipher: KeyCipher
) {
    /** Decrypts and returns the configured Gemini API key, or null when missing. */
    suspend fun get(): String? {
        val settings = settingsRepository.getSettings().getOrNull() ?: return null
        val blob = settings.geminiApiKeyEncrypted
        if (blob.isBlank()) return null
        return runCatching { keyCipher.decrypt(blob) }
            .onFailure { Timber.tag("GeminiVoice").w(it, "Gemini key decrypt failed") }
            .getOrNull()
    }

    /** Stores [apiKey] (plaintext) after encrypting it with [KeyCipher]. */
    suspend fun set(apiKey: String) {
        val trimmed = apiKey.trim()
        val encrypted = if (trimmed.isBlank()) "" else keyCipher.encrypt(trimmed)
        settingsRepository.updateSettings { it.copy(geminiApiKeyEncrypted = encrypted) }
    }
}