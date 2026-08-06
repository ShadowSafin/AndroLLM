package io.androllm.core.cloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.cloud.model.CloudSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.cloudDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cloud_prefs"
)

/** Persistence boundary for [CloudSettings] — implemented by [CloudSettingsStore]. */
interface CloudSettingsRepository {
    val settings: Flow<CloudSettings>
    suspend fun current(): CloudSettings
    suspend fun update(transform: (CloudSettings) -> CloudSettings)
}

/**
 * Persists all cloud settings (providers, favorites, defaults, mode toggle)
 * as a single JSON blob in a dedicated DataStore.
 *
 * API keys are already encrypted by [io.androllm.core.cloud.security.KeyCipher]
 * before they reach this store; nothing here writes plaintext secrets.
 */
@Singleton
class CloudSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) : CloudSettingsRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private object Keys {
        val CLOUD_SETTINGS = stringPreferencesKey("cloud_settings_json")
    }

    private val dataStore: DataStore<Preferences> = context.cloudDataStore

    override val settings: Flow<CloudSettings> = dataStore.data.map { prefs ->
        decode(prefs[Keys.CLOUD_SETTINGS])
    }

    override suspend fun current(): CloudSettings = settings.first()

    override suspend fun update(transform: (CloudSettings) -> CloudSettings) {
        // The decode-transform-encode happens INSIDE edit so DataStore
        // serializes the whole read-modify-write — concurrent updates can
        // never clobber each other.
        dataStore.edit { prefs ->
            val updated = transform(decode(prefs[Keys.CLOUD_SETTINGS]))
            prefs[Keys.CLOUD_SETTINGS] = json.encodeToString(CloudSettings.serializer(), updated)
        }
    }

    private fun decode(raw: String?): CloudSettings {
        if (raw.isNullOrBlank()) return CloudSettings()
        return runCatching { json.decodeFromString(CloudSettings.serializer(), raw) }
            .getOrElse { CloudSettings() }
    }
}
