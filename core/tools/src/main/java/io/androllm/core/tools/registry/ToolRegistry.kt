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
 */
@Singleton
class ToolRegistry @Inject constructor() {

    private val tools = ConcurrentHashMap<String, Tool>()

    /** Registers (or replaces) a tool. Returns true when it was newly added. */
    fun register(tool: Tool): Boolean = tools.put(tool.spec.name, tool) == null

    /** Removes a tool by name. Returns the removed tool, or null. */
    fun unregister(name: String): Tool? = tools.remove(name)

    fun get(name: String): Tool? = tools[name]

    fun contains(name: String): Boolean = tools.containsKey(name)

    /** All registered tools (order is registration order). */
    fun all(): List<Tool> = tools.values.toList()

    fun size(): Int = tools.size

    /** Registers every tool in [batch] (used at startup). */
    fun registerAll(batch: Collection<Tool>) {
        batch.forEach { register(it) }
    }

    /** Clears every registration (used in tests). */
    fun clear() = tools.clear()
}
