package io.androllm.core.datastore

import io.androllm.core.models.ThemeMode
import io.androllm.core.models.UiDensity

/**
 * Snapshot of all user preferences stored in DataStore.
 */
data class UserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "en",
    val developerMode: Boolean = false,
    val storagePath: String = "",
    val firstLaunch: Boolean = true,
    val modelPath: String? = null,
    val fontSize: io.androllm.core.models.ChatFontSize = io.androllm.core.models.ChatFontSize.MEDIUM,
    val markdownEnabled: Boolean = true,
    val codeWrapping: Boolean = false,
    val messageAnimations: Boolean = true,
    val autoScroll: Boolean = true,
    val typingIndicator: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val displayName: String = "",
    val username: String = "",
    val avatarIndex: Int = 0,
    val accentColor: String = "",
    val dynamicColor: Boolean = true,
    val blurIntensity: Float = 0.5f,
    val uiDensity: UiDensity = UiDensity.DEFAULT,
    val chatWallpaper: String = "",
    val reduceMotion: Boolean = false
)