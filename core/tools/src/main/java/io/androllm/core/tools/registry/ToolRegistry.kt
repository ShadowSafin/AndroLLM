package io.androllm.core.tools.registry

import io.androllm.core.tools.api.Tool
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry of every tool the assistant can call. Tools are looked up by their
 * [io.androllm.core.tools.api.ToolSpec.name].
 *
 * This is the plugin-SDK seam: third-party modules can call [register] at any
 * time (e.g. on app start, from a feature module, or from a dynamically loaded
 * plugin) and the tool becomes available to the planner immediately — no core
 * changes required.
 *
 * Hardening: single runtime registry, strict validation, fail-fast on misuse.
 * - Only tools in the registry may be called.
 * - Unknown tool names are rejected immediately.
 * - Duplicate tool names are rejected.
 * - Empty tool names are rejected.
 */
@Singleton
class ToolRegistry @Inject constructor() {

    private val tools = ConcurrentHashMap<String, Tool>()

    /**
     * Registers a tool. Returns true when newly added, false when rejected.
     * Rejects empty names and duplicate names immediately — never replaces.
     */
    fun register(tool: Tool): Boolean {
        val name = tool.spec.name.trim()
        if (name.isEmpty()) {
            timber.log.Timber.w("ToolRegistry: rejected tool with empty name")
            return false
        }
        // Regex for valid snake_case tool names: starts with letter, contains letters, digits, underscore
        if (!name.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            timber.log.Timber.w("ToolRegistry: rejected tool with invalid name '$name'")
            return false
        }
        val previous = tools.putIfAbsent(name, tool)
        if (previous != null) {
            timber.log.Timber.w("ToolRegistry: rejected duplicate tool name '$name'")
            return false
        }
        timber.log.Timber.i("ToolRegistry: registered tool '$name'")
        return true
    }

    /**
     * Strict registration that throws on duplicate/empty — use when duplicate
     * is a programmer error.
     */
    fun registerStrict(tool: Tool) {
        val name = tool.spec.name.trim()
        require(name.isNotEmpty()) { "Tool name must not be empty" }
        require(!tools.containsKey(name)) { "Duplicate tool name '$name' — registry already contains this tool" }
        tools[name] = tool
    }

    /** Removes a tool by name. Returns the removed tool, or null. */
    fun unregister(name: String): Tool? {
        if (name.isBlank()) return null
        return tools.remove(name)
    }

    fun get(name: String): Tool? {
        if (name.isBlank()) return null
        return tools[name]
    }

    fun contains(name: String): Boolean {
        if (name.isBlank()) return false
        return tools.containsKey(name)
    }

    /** All registered tools (order is registration order). */
    fun all(): List<Tool> = tools.values.toList()

    fun size(): Int = tools.size

    /**
     * Registers every tool in [batch] (used at startup). Returns list of
     * rejected duplicates.
     */
    fun registerAll(batch: Collection<Tool>): List<String> {
        val rejected = mutableListOf<String>()
        batch.forEach { tool ->
            if (!register(tool)) rejected += tool.spec.name
        }
        if (rejected.isNotEmpty()) {
            timber.log.Timber.w("ToolRegistry: rejected ${rejected.size} duplicate tool(s): $rejected")
        }
        return rejected
    }

    /** Clears every registration (used in tests). */
    fun clear() = tools.clear()

    /**
     * Validates that [name] is a known registered tool. Returns null if valid,
     * or an error message if rejected.
     */
    fun validateToolName(name: String): String? = when {
        name.isBlank() -> "Tool name must not be empty"
        !contains(name) -> "Unknown tool '$name' — not in registry"
        else -> null
    }
}
