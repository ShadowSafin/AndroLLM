package io.androllm.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.androllm.core.common.AppConstants
import io.androllm.core.models.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.DATASTORE_NAME
)

/**
 * Singleton wrapper around the preferences DataStore.
 */
@Singleton
class PreferencesDataStore @Inject constructor(
    private val context: Context
) {

    private object Keys {
        val THEME = stringPreferencesKey(AppConstants.Preferences.THEME_KEY)
        val LANGUAGE = stringPreferencesKey(AppConstants.Preferences.LANGUAGE_KEY)
        val DEVELOPER_MODE = booleanPreferencesKey(AppConstants.Preferences.DEVELOPER_MODE_KEY)
        val STORAGE_PATH = stringPreferencesKey(AppConstants.Preferences.STORAGE_PATH_KEY)
        val FIRST_LAUNCH = booleanPreferencesKey(AppConstants.Preferences.FIRST_LAUNCH_KEY)
        val MODEL_PATH = stringPreferencesKey(AppConstants.Preferences.MODEL_PATH_KEY)
        val FONT_SIZE = stringPreferencesKey("font_size")
        val MARKDOWN_ENABLED = booleanPreferencesKey("markdown_enabled")
        val CODE_WRAPPING = booleanPreferencesKey("code_wrapping")
        val MESSAGE_ANIMATIONS = booleanPreferencesKey("message_animations")
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll")
        val TYPING_INDICATOR = booleanPreferencesKey("typing_indicator")
    }

    private val dataStore: DataStore<Preferences> = context.preferencesDataStore

    /**
     * Current theme preference.
     */
    val theme: Flow<ThemeMode> = dataStore.data.map { preferences ->
        val value = preferences[Keys.THEME] ?: ThemeMode.SYSTEM.name
        runCatching { ThemeMode.valueOf(value) }.getOrDefault(ThemeMode.SYSTEM)
    }

    /**
     * Current language preference.
     */
    val language: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.LANGUAGE] ?: "en"
    }

    /**
     * Developer mode flag.
     */
    val developerMode: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.DEVELOPER_MODE] ?: false
    }

    /**
     * Preferred storage path for models.
     */
    val storagePath: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.STORAGE_PATH] ?: ""
    }

    /**
     * First launch flag.
     */
    val firstLaunch: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.FIRST_LAUNCH] ?: true
    }

    /**
     * Currently selected model path.
     */
    val modelPath: Flow<String?> = dataStore.data.map { preferences ->
        preferences[Keys.MODEL_PATH]
    }

    /**
     * Snapshot of all preferences.
     */
    val userPreferences: Flow<UserPreferences> = dataStore.data.map { preferences ->
        UserPreferences(
            theme = runCatching { ThemeMode.valueOf(preferences[Keys.THEME] ?: ThemeMode.SYSTEM.name) }
                .getOrDefault(ThemeMode.SYSTEM),
            language = preferences[Keys.LANGUAGE] ?: "en",
            developerMode = preferences[Keys.DEVELOPER_MODE] ?: false,
            storagePath = preferences[Keys.STORAGE_PATH] ?: "",
            firstLaunch = preferences[Keys.FIRST_LAUNCH] ?: true,
            modelPath = preferences[Keys.MODEL_PATH],
            fontSize = runCatching { io.androllm.core.models.ChatFontSize.valueOf(preferences[Keys.FONT_SIZE] ?: "MEDIUM") }
                .getOrDefault(io.androllm.core.models.ChatFontSize.MEDIUM),
            markdownEnabled = preferences[Keys.MARKDOWN_ENABLED] ?: true,
            codeWrapping = preferences[Keys.CODE_WRAPPING] ?: false,
            messageAnimations = preferences[Keys.MESSAGE_ANIMATIONS] ?: true,
            autoScroll = preferences[Keys.AUTO_SCROLL] ?: true,
            typingIndicator = preferences[Keys.TYPING_INDICATOR] ?: true
        )
    }

    suspend fun setTheme(theme: ThemeMode) {
        dataStore.edit { preferences -> preferences[Keys.THEME] = theme.name }
    }

    suspend fun setLanguage(language: String) {
        dataStore.edit { preferences -> preferences[Keys.LANGUAGE] = language }
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.DEVELOPER_MODE] = enabled }
    }

    suspend fun setStoragePath(path: String) {
        dataStore.edit { preferences -> preferences[Keys.STORAGE_PATH] = path }
    }

    suspend fun setFirstLaunch(firstLaunch: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.FIRST_LAUNCH] = firstLaunch }
    }

    suspend fun setModelPath(path: String?) {
        dataStore.edit { preferences ->
            if (path == null) {
                preferences.remove(Keys.MODEL_PATH)
            } else {
                preferences[Keys.MODEL_PATH] = path
            }
        }
    }

    suspend fun setFontSize(size: io.androllm.core.models.ChatFontSize) {
        dataStore.edit { preferences -> preferences[Keys.FONT_SIZE] = size.name }
    }

    suspend fun setMarkdownEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.MARKDOWN_ENABLED] = enabled }
    }

    suspend fun setCodeWrapping(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.CODE_WRAPPING] = enabled }
    }

    suspend fun setMessageAnimations(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.MESSAGE_ANIMATIONS] = enabled }
    }

    suspend fun setAutoScroll(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.AUTO_SCROLL] = enabled }
    }

    suspend fun setTypingIndicator(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.TYPING_INDICATOR] = enabled }
    }

    /**
     * Returns the current theme synchronously.
     */
    suspend fun getTheme(): ThemeMode = theme.first()

    /**
     * Returns whether this is the first launch.
     */
    suspend fun isFirstLaunch(): Boolean = firstLaunch.first()
}
