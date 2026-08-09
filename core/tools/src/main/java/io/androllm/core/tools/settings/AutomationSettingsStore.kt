package io.androllm.core.tools.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.toolsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "automation_preferences"
)

/**
 * Persists [AutomationSettings] in DataStore. Same pattern as the voice
 * settings store: a private preferences file owned by this module.
 */
@Singleton
class AutomationSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val TOOL_CALLING_ENABLED = booleanPreferencesKey("tool_calling_enabled")
        val CONFIRMATION_MODE = stringPreferencesKey("confirmation_mode")
        val DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        val VOICE_CONFIRMATIONS = booleanPreferencesKey("voice_confirmations")
        val MAX_TOOL_ROUNDS = intPreferencesKey("max_tool_rounds")
        val SHOW_TOOL_ACTIVITY = booleanPreferencesKey("show_tool_activity")
    }

    private val dataStore: DataStore<Preferences> = context.toolsDataStore

    val settings: Flow<AutomationSettings> = dataStore.data.map { p ->
        val defaults = AutomationSettings()
        AutomationSettings(
            toolCallingEnabled = p[Keys.TOOL_CALLING_ENABLED] ?: defaults.toolCallingEnabled,
            confirmationMode = runCatching {
                ConfirmationMode.valueOf(p[Keys.CONFIRMATION_MODE] ?: defaults.confirmationMode.name)
            }.getOrDefault(defaults.confirmationMode),
            disabledTools = p[Keys.DISABLED_TOOLS] ?: defaults.disabledTools,
            voiceConfirmations = p[Keys.VOICE_CONFIRMATIONS] ?: defaults.voiceConfirmations,
            maxToolRounds = (p[Keys.MAX_TOOL_ROUNDS] ?: defaults.maxToolRounds).coerceIn(1, 6),
            showToolActivity = p[Keys.SHOW_TOOL_ACTIVITY] ?: defaults.showToolActivity
        )
    }

    suspend fun current(): AutomationSettings = settings.first()

    /** Atomically applies [transform] to the persisted settings. */
    suspend fun update(transform: (AutomationSettings) -> AutomationSettings) {
        dataStore.edit { p ->
            val next = transform(current())
            p[Keys.TOOL_CALLING_ENABLED] = next.toolCallingEnabled
            p[Keys.CONFIRMATION_MODE] = next.confirmationMode.name
            p[Keys.DISABLED_TOOLS] = next.disabledTools
            p[Keys.VOICE_CONFIRMATIONS] = next.voiceConfirmations
            p[Keys.MAX_TOOL_ROUNDS] = next.maxToolRounds
            p[Keys.SHOW_TOOL_ACTIVITY] = next.showToolActivity
        }
    }

    suspend fun setToolCallingEnabled(enabled: Boolean) {
        update { it.copy(toolCallingEnabled = enabled) }
    }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) {
        update {
            it.copy(
                disabledTools = if (enabled) it.disabledTools - toolName
                else it.disabledTools + toolName
            )
        }
    }

    suspend fun setConfirmationMode(mode: ConfirmationMode) {
        update { it.copy(confirmationMode = mode) }
    }
}
