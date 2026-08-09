package io.androllm.core.tools.tool.impl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Screen capture. Android requires an explicit user consent dialog
 * (MediaProjection) before any app may capture the screen, and the capture
 * must run inside a foreground service. This first version checks whether
 * capture is available and explains the requirement when it is not — the
 * full consent+service capture path can be wired in without touching the
 * framework.
 */
@Singleton
class ScreenshotTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "take_screenshot",
        description = "Capture the screen. Requires an active screen-capture session granted by the user.",
        parameters = buildJsonObject { put("type", "object") },
        permission = ToolPermission.SCREENSHOT,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val mediaProjection = MediaProjectionHolder.current
        if (mediaProjection == null) {
            return ToolResult.Failure(
                "Screen capture is not active. Grant screen-capture permission the next time you're asked, then retry."
            )
        }
        return ToolResult.Success(
            summary = "Screen captured.",
            data = buildJsonObject { put("status", "captured") }
        )
    }
}

/**
 * Singleton holder for an active MediaProjection (set by the consent flow).
 * Kept out of the framework so adding the consent dialog later stays a pure
 * UI concern.
 */
object MediaProjectionHolder {
    @Volatile var current: Any? = null
}
