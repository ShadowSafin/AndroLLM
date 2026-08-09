package io.androllm.core.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.androllm.core.tools.registry.ToolRegistry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Manages the lifecycle of every configured MCP server:
 *
 * connect → initialize handshake → list tools → wrap each remote tool in a
 * [McpRemoteTool] and register it in the shared [ToolRegistry] (names
 * `mcp_<server>_<tool>`) → the planner sees them automatically.
 *
 * disconnect → unregister that server's tools and close the client.
 *
 * One [Client] per server; a Mutex keeps connect/disconnect races safe.
 */
@Singleton
class McpConnectionManager @Inject constructor(
    private val settingsStore: McpSettingsStore,
    private val registry: ToolRegistry
) {

    /** Per-server connection state surfaced to Settings → MCP Servers. */
    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(val toolCount: Int) : State
        data class Failed(val message: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val clients = ConcurrentHashMap<String, Client>()
    private val lock = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One HTTP client per server (the Streamable HTTP transport needs Ktor's
     * SSE plugin, bundled in ktor-client-plugins). The optional bearer token
     * goes in via Ktor defaultRequest — a documented API that works with any
     * transport constructor.
     */
    private fun httpClientFor(server: McpServer): HttpClient = HttpClient(OkHttp) {
        install(SSE)
        if (server.authToken.isNotBlank()) {
            defaultRequest {
                header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            }
        }
    }

    /** Connects every enabled server (called at settings screen open / app start). */
    suspend fun connectAll() {
        settingsStore.current().filter { it.enabled }.forEach { connect(it) }
    }

    suspend fun connect(server: McpServer) {
        lock.withLock {
            // Idempotent: an already-connected server (same id) is left alone,
            // so opening Settings doesn't churn every connection.
            if (_states.value[server.id] is State.Connected) return@withLock
            unregisterTools(server.id)
            clients.remove(server.id)?.let { runCatching { it.close() } }
            _states.update { it + (server.id to State.Connecting) }
            runCatching {
                val client = Client(
                    clientInfo = Implementation(name = "AndroLLM", version = "1.0.0")
                )
                val transport = StreamableHttpClientTransport(
                    client = httpClientFor(server),
                    url = server.url
                )
                client.connect(transport)
                val tools = client.listTools().tools
                clients[server.id] = client
                val registered = tools.map { remote ->
                    McpRemoteTool(server.id, remote, { clients[server.id] }, json)
                        .also { registry.register(it) }
                }
                _states.update { it + (server.id to State.Connected(registered.size)) }
                Timber.i("McpConnectionManager: '${server.name}' connected, ${registered.size} tool(s) imported")
            }.onFailure { t ->
                runCatching { clients.remove(server.id)?.close() }
                unregisterTools(server.id)
                _states.update { it + (server.id to State.Failed(t.message ?: t.javaClass.simpleName)) }
                Timber.w(t, "McpConnectionManager: '${server.name}' connect failed")
            }
        }
    }

    suspend fun disconnect(serverId: String) {
        lock.withLock {
            unregisterTools(serverId)
            clients.remove(serverId)?.let { runCatching { it.close() } }
            // Drop the state entry entirely: the UI renders a missing entry as
            // "Offline", and removed servers never linger in the map.
            _states.update { it - serverId }
        }
    }

    suspend fun disconnectAll() {
        settingsStore.current().forEach { disconnect(it.id) }
    }

    /** Unregisters every tool imported from [serverId] (prefix match). */
    private fun unregisterTools(serverId: String) {
        val prefix = "mcp_${serverId}_"
        registry.all()
            .filter { it.spec.name.startsWith(prefix) }
            .forEach { registry.unregister(it.spec.name) }
    }
}
