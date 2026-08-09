package io.androllm.core.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.mcpDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mcp_servers"
)

/**
 * Persists the configured [McpServer] list as a JSON array in a private
 * DataStore preferences file (same pattern as the automation settings store).
 */
@Singleton
class McpSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val SERVERS = stringPreferencesKey("servers_json")
    }

    private val dataStore: DataStore<Preferences> = context.mcpDataStore

    val servers: Flow<List<McpServer>> = dataStore.data.map { p ->
        val raw = p[Keys.SERVERS].orEmpty()
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<McpServer>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun current(): List<McpServer> = servers.first()

    suspend fun add(server: McpServer) = update { list -> list + server }

    suspend fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = update { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    private suspend fun update(transform: (List<McpServer>) -> List<McpServer>) {
        dataStore.edit { p ->
            p[Keys.SERVERS] = json.encodeToString(transform(current()))
        }
    }
}
