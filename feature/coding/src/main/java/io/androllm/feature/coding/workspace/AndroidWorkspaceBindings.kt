package io.androllm.feature.coding.workspace

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.codingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "coding_preferences"
)

/**
 * App workspace root in PUBLIC shared storage so every created workspace is a
 * folder the user can see and browse with any file manager:
 * `/storage/emulated/0/AndroLLM/workspaces`.
 *
 * Falls back to app-private storage only when external storage is not
 * mounted. The old app-private root is exposed as [legacyRootDir] so
 * workspaces created by earlier versions remain listed and usable.
 */
class AndroidWorkspaceRootProvider(private val context: Context) : WorkspaceRootProvider {

    override fun rootDir(): File {
        val mounted = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        return if (mounted) {
            File(Environment.getExternalStorageDirectory(), "AndroLLM/workspaces")
        } else {
            legacyDir()
        }
    }

    override fun legacyRootDir(): File = legacyDir()

    private fun legacyDir(): File = File(context.filesDir, "coding-workspaces")
}

/**
 * DataStore-backed [WorkspaceStore] for the coding feature. Uses its own
 * preferences file (`coding_preferences`) so coding state never mingles with
 * the global app preferences.
 */
class DataStoreWorkspaceStore(private val context: Context) : WorkspaceStore {

    private object Keys {
        val ACTIVE_WORKSPACE_ID = stringPreferencesKey("coding_active_workspace_id")
        val SESSION_STATE_JSON = stringPreferencesKey("coding_session_state_json")
        val WORKSPACE_REGISTRY_JSON = stringPreferencesKey("coding_workspace_registry_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveActiveWorkspaceId(id: String) {
        context.codingDataStore.edit { it[Keys.ACTIVE_WORKSPACE_ID] = id }
    }

    override suspend fun loadActiveWorkspaceId(): String =
        context.codingDataStore.data.first()[Keys.ACTIVE_WORKSPACE_ID].orEmpty()

    override suspend fun saveSession(state: CodingSessionState) {
        val encoded = json.encodeToString(state)
        context.codingDataStore.edit { it[Keys.SESSION_STATE_JSON] = encoded }
    }

    override suspend fun loadSession(): CodingSessionState {
        val raw = context.codingDataStore.data.first()[Keys.SESSION_STATE_JSON]
        return if (raw.isNullOrBlank()) {
            CodingSessionState()
        } else {
            runCatching { json.decodeFromString<CodingSessionState>(raw) }.getOrDefault(CodingSessionState())
        }
    }

    override suspend fun loadRegistry(): List<CodingWorkspace> {
        val raw = context.codingDataStore.data.first()[Keys.WORKSPACE_REGISTRY_JSON]
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<CodingWorkspace>>(raw) }.getOrDefault(emptyList())
    }

    override suspend fun saveRegistry(workspaces: List<CodingWorkspace>) {
        val encoded = json.encodeToString(workspaces)
        context.codingDataStore.edit { it[Keys.WORKSPACE_REGISTRY_JSON] = encoded }
    }

    override suspend fun clear() {
        context.codingDataStore.edit { prefs ->
            prefs.remove(Keys.ACTIVE_WORKSPACE_ID)
            prefs.remove(Keys.SESSION_STATE_JSON)
        }
    }
}
