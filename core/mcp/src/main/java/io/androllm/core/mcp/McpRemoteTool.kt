package io.androllm.core.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpSdkTool
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Adapts a remote MCP tool into the local [Tool] contract. Registered into the
 * [io.androllm.core.tools.registry.ToolRegistry] under `mcp_<server>_<tool>`
 * by [McpConnectionManager]; the planner sees it exactly like a built-in tool.
 *
 * The remote [McpSdkTool]'s JSON-Schema `inputSchema` is normalized into the
 * `{type: object, properties, required}` shape [ToolSpec.parameters] expects.
 */
class McpRemoteTool(
    private val serverId: String,
    private val remote: McpSdkTool,
    private val clientProvider: () -> Client?,
    private val json: Json
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = "mcp_${serverId}_${remote.name}",
        description = buildString {
            append(remote.description?.trim()?.takeIf { it.isNotBlank() } ?: "Remote tool from MCP server '$serverId'.")
            append(" (via MCP server '").append(serverId).append("')")
        },
        parameters = buildParameters(json, remote),
        // Remote servers can do anything — the user controls the MCP connection
        // itself in Settings → MCP; every call still respects the Automation
        // permission gate and the executor's timeout.
        permission = ToolPermission.MCP,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val client = clientProvider()
            ?: return ToolResult.Failure(
                "MCP server '$serverId' is not connected. Reconnect it in Settings → MCP Servers.",
                retryable = false
            )
        return runCatching {
            val result = client.callTool(
                CallToolRequest(
                    CallToolRequestParams(
                        name = remote.name,
                        arguments = arguments
                    )
                )
            )
            val text = flatten(result.content)
            if (result.isError == true) ToolResult.Failure(text) else ToolResult.Success(text)
        }.getOrElse { t ->
            Timber.w(t, "McpRemoteTool: '${remote.name}' call failed")
            ToolResult.Failure("MCP tool '${remote.name}' failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun flatten(content: List<*>): String {
        if (content.isEmpty()) return "OK (no content)"
        return content.joinToString("\n") { c ->
            // Never dump raw image/audio data (base64) into the LLM context —
            // describe the block type instead: [ImageContent], [EmbeddedResource]…
            (c as? TextContent)?.text ?: "[${c?.javaClass?.simpleName ?: "content"}]"
        }
    }

    private companion object {
        /**
         * Normalizes the SDK `Tool.inputSchema` into our JSON-Schema shape.
         * The schema is round-tripped through JSON so this works no matter how
         * the SDK models it (raw JsonObject or ToolSchema data class).
         */
        fun buildParameters(json: Json, remote: McpSdkTool): JsonObject = runCatching {
            val serialized = json.encodeToString(McpSdkTool.serializer(), remote)
            val toolJson = json.parseToJsonElement(serialized).jsonObject
            val schema = toolJson["inputSchema"] as? JsonObject ?: JsonObject(emptyMap())
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = schema["required"] as? JsonArray
            buildJsonObject {
                put("type", "object")
                put("properties", properties)
                required?.let { put("required", it) }
            }
        }.getOrElse {
            buildJsonObject { put("type", "object") }
        }
    }
}
