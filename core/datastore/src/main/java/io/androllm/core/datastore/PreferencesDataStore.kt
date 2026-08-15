package io.androllm.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        val FAVORITE_PROMPT_IDS = stringSetPreferencesKey("favorite_prompt_ids")
        val ONBOARDING_COMPLETED = booleanPreferencesKey(AppConstants.Preferences.ONBOARDING_COMPLETED_KEY)
        val SETUP_COMPLETED = booleanPreferencesKey(AppConstants.Preferences.SETUP_COMPLETED_KEY)
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val USERNAME = stringPreferencesKey("username")
        val AVATAR_INDEX = intPreferencesKey("avatar_index")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FORCE_CPU_BACKEND = booleanPreferencesKey("force_cpu_backend")
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
            typingIndicator = preferences[Keys.TYPING_INDICATOR] ?: true,
            onboardingCompleted = preferences[Keys.ONBOARDING_COMPLETED] ?: false,
            displayName = preferences[Keys.DISPLAY_NAME] ?: "",
            username = preferences[Keys.USERNAME] ?: "",
            avatarIndex = preferences[Keys.AVATAR_INDEX] ?: 0,
            accentColor = preferences[Keys.ACCENT_COLOR] ?: ""
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
     * Prompt library favorites (persisted prompt ids).
     */
    val favoritePromptIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[Keys.FAVORITE_PROMPT_IDS] ?: emptySet()
    }

    /**
     * Marks a prompt library entry as favorite.
     */
    suspend fun setPromptFavorite(id: String, favorite: Boolean) {
        dataStore.edit { preferences ->
            val current = preferences[Keys.FAVORITE_PROMPT_IDS] ?: emptySet()
            val updated = if (favorite) current + id else current - id
            preferences[Keys.FAVORITE_PROMPT_IDS] = updated
        }
    }

    /**
     * Whether the user has completed the onboarding introduction.
     */
    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.ONBOARDING_COMPLETED] ?: false
    }

    /**
     * Display name chosen during profile setup.
     */
    val displayName: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.DISPLAY_NAME] ?: ""
    }

    /**
     * Optional username chosen during profile setup.
     */
    val username: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.USERNAME] ?: ""
    }

    /**
     * Avatar index (gradient preset) chosen during profile setup.
     */
    val avatarIndex: Flow<Int> = dataStore.data.map { preferences ->
        preferences[Keys.AVATAR_INDEX] ?: 0
    }

    /**
     * Accent color (ARGB hex, e.g. "FFFF7043") chosen during profile setup.
     */
    val accentColor: Flow<String> = dataStore.data.map { preferences ->
        preferences[Keys.ACCENT_COLOR] ?: ""
    }

    /**
     * Marks the onboarding introduction as completed (or resets it).
     */
    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.ONBOARDING_COMPLETED] = completed }
    }

    /**
     * Whether the first-launch permission/access setup has been completed
     * (see feature:setup). Defaults to false so every fresh account is walked
     * through the setup once; the screen itself never traps the user.
     */
    val setupCompleted: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.SETUP_COMPLETED] ?: false
    }

    /**
     * Marks the permission/access setup as completed (or resets it, e.g. when
     * a future version introduces new permissions and wants to re-show a
     * lightweight setup screen).
     */
    suspend fun setSetupCompleted(completed: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.SETUP_COMPLETED] = completed }
    }

    /**
     * Debug-only: force the native engine to load models on the CPU backend
     * (gpuLayers = 0) instead of offloading layers to Vulkan. Used to bisect
     * GPU-vs-CPU output corruption and compare token speeds. Defaults to false.
     */
    val forceCpuBackend: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.FORCE_CPU_BACKEND] ?: false
    }

    suspend fun setForceCpuBackend(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[Keys.FORCE_CPU_BACKEND] = enabled }
    }

    /**
     * Persists the display name chosen during profile setup.
     */
    suspend fun setDisplayName(name: String) {
        dataStore.edit { preferences -> preferences[Keys.DISPLAY_NAME] = name }
    }

    /**
     * Persists the optional username chosen during profile setup.
     */
    suspend fun setUsername(username: String) {
        dataStore.edit { preferences -> preferences[Keys.USERNAME] = username }
    }

    /**
     * Persists the avatar preset index chosen during profile setup.
     */
    suspend fun setAvatarIndex(index: Int) {
        dataStore.edit { preferences -> preferences[Keys.AVATAR_INDEX] = index }
    }

    /**
     * Persists the accent color (ARGB hex) chosen during profile setup.
     */
    suspend fun setAccentColor(argbHex: String) {
        dataStore.edit { preferences -> preferences[Keys.ACCENT_COLOR] = argbHex }
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
