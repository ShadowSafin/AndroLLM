package io.androllm.core.accessibility.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.accessibilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "accessibility_preferences"
)

/** Persists [AccessibilitySettings] in DataStore (module-owned file). */
@Singleton
class AccessibilitySettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val AUTO_SCROLL = booleanPreferencesKey("auto_scroll_into_view")
        val LLM_PLANNING = booleanPreferencesKey("llm_planning")
        val CONFIRM_HIGH_RISK = booleanPreferencesKey("confirm_high_risk")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val OCR_ENABLED = booleanPreferencesKey("ocr_enabled")
        val STATUS_NOTIFICATION = booleanPreferencesKey("status_notification")
    }

    private val dataStore: DataStore<Preferences> = context.accessibilityDataStore

    val settings: Flow<AccessibilitySettings> = dataStore.data.map { p ->
        val defaults = AccessibilitySettings()
        AccessibilitySettings(
            autoScrollIntoView = p[Keys.AUTO_SCROLL] ?: defaults.autoScrollIntoView,
            llmPlanning = p[Keys.LLM_PLANNING] ?: defaults.llmPlanning,
            confirmHighRisk = p[Keys.CONFIRM_HIGH_RISK] ?: defaults.confirmHighRisk,
            maxSteps = (p[Keys.MAX_STEPS] ?: defaults.maxSteps).coerceIn(2, 40),
            developerMode = p[Keys.DEVELOPER_MODE] ?: defaults.developerMode,
            ocrEnabled = p[Keys.OCR_ENABLED] ?: defaults.ocrEnabled,
            showStatusNotification = p[Keys.STATUS_NOTIFICATION] ?: defaults.showStatusNotification
        )
    }

    suspend fun current(): AccessibilitySettings = settings.first()

    /** Atomically applies [transform] to the persisted settings. */
    suspend fun update(transform: (AccessibilitySettings) -> AccessibilitySettings) {
        dataStore.edit { p ->
            val next = transform(current())
            p[Keys.AUTO_SCROLL] = next.autoScrollIntoView
            p[Keys.LLM_PLANNING] = next.llmPlanning
            p[Keys.CONFIRM_HIGH_RISK] = next.confirmHighRisk
            p[Keys.MAX_STEPS] = next.maxSteps
            p[Keys.DEVELOPER_MODE] = next.developerMode
            p[Keys.OCR_ENABLED] = next.ocrEnabled
            p[Keys.STATUS_NOTIFICATION] = next.showStatusNotification
        }
    }
}
