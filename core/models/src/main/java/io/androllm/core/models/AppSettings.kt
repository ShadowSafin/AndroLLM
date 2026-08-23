package io.androllm.core.models

import kotlinx.serialization.Serializable

/**
 * Application-wide settings stored in the database.
 */
@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "en",
    val storagePath: String = "",
    val developerMode: Boolean = false,
    val firstLaunch: Boolean = true,
    val modelPath: String? = null,
    val fontSize: ChatFontSize = ChatFontSize.MEDIUM,
    val markdownEnabled: Boolean = true,
    val codeWrapping: Boolean = false,
    val messageAnimations: Boolean = true,
    val autoScroll: Boolean = true,
    val typingIndicator: Boolean = true,
    /**
     * Keystore-encrypted Gemini API key used by the voice assistant for
     * Speech-to-Text and Text-to-Speech. Independent of the selected chat
     * provider — the voice assistant always talks to Gemini for STT/TTS, but
     * the chat engine still routes through the user's chosen model.
     */
    val geminiApiKeyEncrypted: String = "",
    /**
     * Warn before opening AI-generated external links. When enabled, tapping
     * a link in an assistant response shows a confirmation dialog that the
     * link was found by AI and may be external. Default ON for safety.
     */
    val warnBeforeOpeningAiLinks: Boolean = true
)

@Serializable
enum class ChatFontSize {
    SMALL, MEDIUM, LARGE
}
