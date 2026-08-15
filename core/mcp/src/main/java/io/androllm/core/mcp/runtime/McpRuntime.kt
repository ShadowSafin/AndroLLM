package io.androllm.core.mcp.runtime

import io.androllm.core.mcp.McpConnectionManager
import io.androllm.core.mcp.McpSettingsStore
import io.androllm.core.runtime.Runtime
import io.androllm.core.runtime.RuntimeCategory
import io.androllm.core.runtime.RuntimeStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the MCP runtime (Model Context Protocol clients) into the
 * central [io.androllm.core.runtime.RuntimeRegistry]. Mirrors the shared
 * [McpSettingsStore] and [McpConnectionManager]; it never connects or
 * disconnects a server.
 */
@Singleton
class McpRuntime @Inject constructor(
    private val settingsStore: McpSettingsStore,
    private val connectionManager: McpConnectionManager
) : Runtime {

    override val id = "mcp"
    override val displayName = "MCP Servers"
    override val category = RuntimeCategory.MCP
    override val description = "Model Context Protocol clients — remote tools from external MCP servers, registered into the tool registry."

    override suspend fun status(): RuntimeStatus = runCatching {
        val servers = settingsStore.current()
        val connected = connectionManager.states.value.values.count { it is McpConnectionManager.State.Connected }
        if (servers.isEmpty()) {
            RuntimeStatus(
                available = false,
                summary = "No MCP servers configured",
                detail = "Add a server in Settings → MCP Servers to import remote tools."
            )
        } else {
            RuntimeStatus(true, "${servers.size} server(s) configured, $connected connected")
        }
    }.getOrElse { e ->
        RuntimeStatus(false, "Status check failed", e.message ?: e.javaClass.simpleName)
    }
}
