package io.androllm.core.mcp

import kotlinx.serialization.Serializable

/**
 * A configured MCP server the assistant can connect to over the Streamable
 * HTTP transport. Its remote tools are imported into the [io.androllm.core.tools.registry.ToolRegistry]
 * under `mcp_<server>_<tool>` names and become visible to the planner like
 * any built-in tool.
 */
@Serializable
data class McpServer(
    /** Stable slug used as the tool-name prefix and the registry scope. */
    val id: String,
    val name: String,
    /** Server endpoint, e.g. "https://example.com/mcp". */
    val url: String,
    /** Optional Bearer token sent as `Authorization: Bearer <token>`. */
    val authToken: String = "",
    val enabled: Boolean = true
) {
    companion object {
        /**
         * Builds a server with a stable id derived from [name] (lowercase,
         * non-alphanumeric → `_`). Used by the settings UI on create.
         */
        fun fromName(name: String, url: String, authToken: String = ""): McpServer =
            McpServer(
                id = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_').ifBlank { "server" },
                name = name.trim(),
                url = url.trim(),
                authToken = authToken.trim()
            )
    }
}
