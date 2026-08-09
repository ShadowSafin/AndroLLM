package io.androllm.core.tools.tool.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Opens the user's email app with a pre-filled draft. Confirmed first; the
 * user presses send in their own mail client — the tool never sends anything
 * by itself.
 */
@Singleton
class EmailTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "send_email",
        description = "Open the email app with a pre-filled draft (recipient, subject, body) for the user to review and send. Always requires confirmation.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("to") {
                    put("type", "string")
                    put("description", "Recipient email address")
                }
                putJsonObject("subject") { put("type", "string") }
                putJsonObject("body") { put("type", "string") }
            }
            putJsonArray("required") { add("to") }
        },
        permission = ToolPermission.EMAIL,
        requiresConfirmation = true,
        confirmationPrompt = "open the email to {to}",
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val to = ToolArgs.str(arguments, "to", "recipient")
            ?: return ToolResult.Failure("Missing required argument: to")
        val subject = ToolArgs.str(arguments, "subject").orEmpty()
        val body = ToolArgs.str(arguments, "body", "message").orEmpty()

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val launched = ToolIntents.launch(context, intent)
        if (!launched) {
            return ToolResult.Failure("No email app found to compose to $to.")
        }
        return ToolResult.Success(
            summary = "Email draft opened for $to${if (subject.isNotBlank()) " — \"$subject\"" else ""}.",
            data = buildJsonObject {
                put("to", to)
                put("subject", subject)
                put("status", "draft-opened")
            }
        )
    }
}
