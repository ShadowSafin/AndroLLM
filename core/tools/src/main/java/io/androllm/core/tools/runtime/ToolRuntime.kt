package io.androllm.core.tools.runtime

import io.androllm.core.runtime.Runtime
import io.androllm.core.runtime.RuntimeCategory
import io.androllm.core.runtime.RuntimeStatus
import io.androllm.core.tools.registry.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the tool-calling runtime (planner, executor, registry and every
 * built-in + MCP tool) into the central
 * [io.androllm.core.runtime.RuntimeRegistry]. Mirrors the shared
 * [ToolRegistry] that the planner and MCP already use — no duplication.
 */
@Singleton
class ToolRuntime @Inject constructor(
    private val registry: ToolRegistry
) : Runtime {

    override val id = "tools"
    override val displayName = "Tool Calling"
    override val category = RuntimeCategory.TOOLS
    override val description = "The assistant's tool system: planner, executor, registry and built-in tools."

    override suspend fun status(): RuntimeStatus = runCatching {
        val count = registry.size()
        RuntimeStatus(
            available = count > 0,
            summary = "$count tool(s) registered",
            detail = if (count > 0) null else "No tools registered — tool calling unavailable."
        )
    }.getOrElse { e ->
        RuntimeStatus(false, "Status check failed", e.message ?: e.javaClass.simpleName)
    }
}
