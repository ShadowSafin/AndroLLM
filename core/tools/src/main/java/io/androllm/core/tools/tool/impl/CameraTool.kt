package io.androllm.core.tools.tool.impl

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opens the camera app. Launching full-screen apps from the background is
 * restricted on Android 10+; when the system blocks the start the tool says
 * so instead of crashing.
 */
@Singleton
class CameraTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_camera",
        description = "Open the camera app.",
        parameters = buildJsonObject { put("type", "object") },
        permission = ToolPermission.CAMERA,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val launched = ToolIntents.launch(context, intent)
        if (!launched) {
            return ToolResult.Failure(
                "Android blocked opening the camera from the background. Open the camera app manually."
            )
        }
        return ToolResult.Success(
            summary = "Opening the camera.",
            data = buildJsonObject { put("status", "opened") }
        )
    }
}
