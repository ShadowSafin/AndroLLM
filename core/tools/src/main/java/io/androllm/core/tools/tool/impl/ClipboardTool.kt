package io.androllm.core.tools.tool.impl

import android.content.ClipData
import android.content.ClipboardManager
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Copies generated text to the system clipboard ("copy that answer"). */
@Singleton
class ClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "copy_to_clipboard",
        description = "Copy text to the clipboard. Use when the user asks to copy or save text.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "The text to copy")
                }
            }
            putJsonArray("required") { add("text") }
        },
        permission = ToolPermission.CLIPBOARD,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text", "content")
            ?: return ToolResult.Failure("Missing required argument: text")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AndroLLM", text))
        return ToolResult.Success(
            summary = "Copied ${text.length} characters to the clipboard.",
            data = buildJsonObject {
                put("length", text.length)
                put("status", "copied")
            }
        )
    }
}
