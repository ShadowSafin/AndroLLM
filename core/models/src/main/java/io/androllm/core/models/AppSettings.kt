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
    val typingIndicator: Boolean = true
)

@Serializable
enum class ChatFontSize {
    SMALL, MEDIUM, LARGE
}
