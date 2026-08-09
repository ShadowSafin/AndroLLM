package io.androllm.core.tools.api

import kotlinx.serialization.json.JsonObject

/**
 * A single capability the assistant can execute on the user's device or over
 * the network. Implementations are plain Android services — they NEVER contain
 * LLM logic. New tools are added by implementing this interface and
 * registering them in [io.androllm.core.tools.registry.ToolRegistry]; nothing
 * else in the framework needs to change (plugin SDK contract).
 */
interface Tool {

    /** Static, model-visible description of this tool. */
    val spec: ToolSpec

    /**
     * Executes the tool with the parsed [arguments]. Must be fast, never block
     * the caller indefinitely (the executor applies a timeout) and always
     * return a [ToolResult] instead of throwing.
     */
    suspend fun execute(arguments: JsonObject): ToolResult
}
