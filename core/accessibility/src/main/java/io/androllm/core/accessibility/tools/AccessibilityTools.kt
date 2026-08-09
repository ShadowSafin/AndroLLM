package io.androllm.core.accessibility.tools

import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.executor.AutomationExecutor
import io.androllm.core.accessibility.finder.UiSelector
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.tool.impl.ToolArgs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `ui_*` tool family. These are the LAST resort in the execution
 * priority (native API → MCP tool → official app intent → accessibility):
 * they drive other apps through the accessibility service when no dedicated
 * tool exists. The planner prompt steers the model to prefer native tools.
 */

@Singleton
class UiReadScreenTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "ui_read_screen",
        description = "Read the current screen of the foreground app: visible buttons, text fields, lists, dialogs and the focused element. Use before acting in another app.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max elements to return (default 40)")
                }
            }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!controller.isConnected) return ToolResult.Failure(notConnected())
        val limit = ToolArgs.int(arguments, "limit", 40).coerceIn(5, 150)
        val screen = controller.readScreen()
        return ToolResult.Success(
            summary = screen.describe(limit),
            data = buildJsonObject {
                put("app", screen.packageName)
                put("fields", screen.textFields.size)
                put("buttons", screen.buttons.size)
            }
        )
    }
}

@Singleton
class UiClickTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "ui_click",
        description = "Tap an element in the foreground app by its visible label (e.g. 'Search', 'Send', 'Allow'). Scrolls to the element first if needed.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "The visible text or content description of the element to tap")
                }
                putJsonObject("index") {
                    put("type", "integer")
                    put("description", "Which match to use when several elements share the label (default 0)")
                }
            }
            putJsonArray("required") { add("label") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!controller.isConnected) return ToolResult.Failure(notConnected())
        val label = ToolArgs.str(arguments, "label", "target", "text")
            ?: return ToolResult.Failure("Missing required argument: label")
        val index = ToolArgs.int(arguments, "index", 0)
        val result = controller.click(UiSelector(textContains = label, index = index))
        return if (result.success) {
            ToolResult.Success(result.message)
        } else {
            ToolResult.Failure(result.message)
        }
    }
}

@Singleton
class UiTypeTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "ui_type",
        description = "Type text into the focused text field of the foreground app (or the field matching 'into').",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("into") {
                    put("type", "string")
                    put("description", "Optional hint/label of the field to fill")
                }
            }
            putJsonArray("required") { add("text") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!controller.isConnected) return ToolResult.Failure(notConnected())
        val text = ToolArgs.str(arguments, "text", "value", "content")
            ?: return ToolResult.Failure("Missing required argument: text")
        val into = ToolArgs.str(arguments, "into", "field")
        val result = controller.type(text, into)
        return if (result.success) ToolResult.Success(result.message) else ToolResult.Failure(result.message)
    }
}

@Singleton
class UiScrollTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "ui_scroll",
        description = "Scroll the foreground app vertically or horizontally (direction: up, down, left, right).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("up", "down", "left", "right").forEach { add(it) } }
                }
            }
            putJsonArray("required") { add("direction") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!controller.isConnected) return ToolResult.Failure(notConnected())
        val direction = ToolArgs.str(arguments, "direction") ?: "down"
        val result = controller.scroll(direction)
        return if (result.success) ToolResult.Success(result.message) else ToolResult.Failure(result.message)
    }
}

@Singleton
class UiSwipeTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "ui_swipe",
        description = "Swipe the screen (direction: up, down, left, right).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("up", "down", "left", "right").forEach { add(it) } }
                }
            }
            putJsonArray("required") { add("direction") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!controller.isConnected) return ToolResult.Failure(notConnected())
        val direction = ToolArgs.str(arguments, "direction") ?: "down"
        val result = controller.swipe(direction)
        return if (result.success) ToolResult.Success(result.message) else ToolResult.Failure(result.message)
    }
}

@Singleton
class UiNavigateTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "ui_navigate",
        description = "System navigation: back, home, recents, notifications, quick_settings, split_screen.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("back", "home", "recents", "notifications", "quick_settings", "split_screen")
                            .forEach { add(it) }
                    }
                }
            }
            putJsonArray("required") { add("action") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!controller.isConnected) return ToolResult.Failure(notConnected())
        val action = ToolArgs.str(arguments, "action", "name")
            ?: return ToolResult.Failure("Missing required argument: action")
        val result = controller.navigate(action)
        return if (result.success) ToolResult.Success(result.message) else ToolResult.Failure(result.message)
    }
}

/**
 * The flagship tool: executes a full multi-step task in another app
 * ("order an Uber to the airport"). The executor reads the screen, plans
 * step by step (LLM when available), acts, and asks before anything risky.
 */
@Singleton
class UiRunTaskTool @Inject constructor(
    private val executor: AutomationExecutor
) : Tool {

    override val spec = ToolSpec(
        name = "ui_run",
        description = "Perform a multi-step task inside another app by reading the screen and tapping/typing step by step (e.g. 'search YouTube for Android 17', 'send a WhatsApp message'). Ask for confirmation before anything that sends, pays, books or deletes.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("goal") {
                    put("type", "string")
                    put("description", "What to do, in plain language, including any text to type or search")
                }
            }
            putJsonArray("required") { add("goal") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE,
        // Multi-step UI tasks outlive the default 20s tool budget — the
        // executor's own step limit guards the loop instead.
        executionTimeoutMs = 180_000L
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val goal = ToolArgs.str(arguments, "goal", "task", "query")
            ?: return ToolResult.Failure("Missing required argument: goal")
        val result = executor.run(goal)
        return if (result.success) {
            ToolResult.Success(
                summary = "Task complete in ${result.steps} step(s): ${result.summary}",
                data = buildJsonObject {
                    put("steps", result.steps)
                    put("summary", result.summary)
                }
            )
        } else {
            ToolResult.Failure(result.summary)
        }
    }
}

/** Shared helper for the "service disabled" failure message. */
private fun notConnected(): String =
    "The accessibility service is not enabled. Open Settings → Accessibility → AndroLLM UI Automation and turn it on, then ask again."
