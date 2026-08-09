package io.androllm.core.accessibility.tools

import io.androllm.core.accessibility.controller.AccessibilityController
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
 * Advanced gestures on top of the basic tap/type/scroll/swipe tools:
 * double-tap, long-press, drag (by element or by points), and two-finger
 * pinch in/out. Element-based gestures accept a `label`; coordinate-based
 * ones accept `dx`/`dy` (drag) or `direction` (swipe).
 */
@Singleton
class UiGestureTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "ui_gesture",
        description = "Advanced touch gesture in the foreground app. action: double_tap (label), long_press (label), drag (label + dx/dy points), pinch_in or pinch_out (screen center), swipe (direction).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("double_tap", "long_press", "drag", "pinch_in", "pinch_out", "swipe")
                            .forEach { add(it) }
                    }
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Visible text of the element to act on (double_tap, long_press, drag)")
                }
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("up", "down", "left", "right").forEach { add(it) } }
                }
                putJsonObject("dx") { put("type", "number"); put("description", "Horizontal drag distance in points" ) }
                putJsonObject("dy") { put("type", "number"); put("description", "Vertical drag distance in points" ) }
            }
            putJsonArray("required") { add("action") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!controller.isConnected) return ToolResult.Failure(notConnected())
        val action = ToolArgs.str(arguments, "action", "type")
            ?: return ToolResult.Failure("Missing required argument: action")
        val label = ToolArgs.str(arguments, "label", "target", "text")
        return when (action.lowercase()) {
            "double_tap", "doubletap" -> {
                if (label == null) return ToolResult.Failure("double_tap needs a label")
                result(controller.doubleTap(UiSelector(textContains = label)))
            }
            "long_press", "longpress" -> {
                if (label == null) return ToolResult.Failure("long_press needs a label")
                result(controller.longClick(UiSelector(textContains = label)))
            }
            "drag" -> {
                if (label == null) return ToolResult.Failure("drag needs a label")
                val dx = ToolArgs.double(arguments, "dx")?.toFloat() ?: 0f
                val dy = ToolArgs.double(arguments, "dy")?.toFloat() ?: 0f
                if (dx == 0f && dy == 0f) return ToolResult.Failure("drag needs dx or dy")
                result(controller.drag(UiSelector(textContains = label), dx, dy))
            }
            "swipe" -> {
                val direction = ToolArgs.str(arguments, "direction") ?: "down"
                result(controller.swipe(direction))
            }
            "pinch_in", "pinchin" -> result(controller.pinch(zoomIn = true))
            "pinch_out", "pinchout" -> result(controller.pinch(zoomIn = false))
            else -> ToolResult.Failure("Unknown gesture action '$action'. Use double_tap, long_press, drag, pinch_in, pinch_out or swipe.")
        }
    }

    private fun result(r: io.androllm.core.accessibility.controller.UiActionResult): ToolResult =
        if (r.success) ToolResult.Success(r.message) else ToolResult.Failure(r.message)

    private fun notConnected(): String =
        "The accessibility service is not enabled. Open Settings → Accessibility → AndroLLM UI Automation and turn it on, then ask again."
}
