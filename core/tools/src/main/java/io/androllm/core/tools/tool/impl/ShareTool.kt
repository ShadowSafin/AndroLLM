package io.androllm.core.tools.tool.impl

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opens the Android share sheet with the given text ("share this answer").
 */
@Singleton
class ShareTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "share_text",
        description = "Open the share sheet with text so the user can send it to another app or person.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to share")
                }
            }
            putJsonArray("required") { add("text") }
        },
        permission = ToolPermission.SHARE,
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text", "content")
            ?: return ToolResult.Failure("Missing required argument: text")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Share with…")
        val launched = ToolIntents.launch(context, chooser)
        if (!launched) {
            return ToolResult.Failure("No app could handle the share request.")
        }
        return ToolResult.Success(
            summary = "Share sheet opened.",
            data = buildJsonObject {
                put("status", "opened")
                put("length", text.length)
            }
        )
    }
}
