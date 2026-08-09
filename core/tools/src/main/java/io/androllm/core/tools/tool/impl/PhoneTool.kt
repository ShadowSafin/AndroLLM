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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opens the dialer pre-filled with the number (ACTION_DIAL — the safest call
 * path: no CALL_PHONE permission, the user still presses the final button).
 * Always confirmed, like sending an SMS.
 */
@Singleton
class PhoneTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "make_call",
        description = "Open the dialer with a phone number so the user can make a call. Always requires the user's confirmation.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("phone") {
                    put("type", "string")
                    put("description", "Phone number with country code, e.g. +919876543210")
                }
                putJsonObject("contact") {
                    put("type", "string")
                    put("description", "Optional contact name for display")
                }
            }
            putJsonArray("required") { add("phone") }
        },
        permission = ToolPermission.CALLS,
        requiresConfirmation = true,
        confirmationPrompt = "call {contact}",
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val phone = ToolArgs.str(arguments, "phone", "number", "to")
            ?: return ToolResult.Failure("Missing required argument: phone")
        val contact = ToolArgs.str(arguments, "contact")

        val launched = ToolIntents.launch(context, ToolIntents.dialUri(phone))
        if (!launched) {
            return ToolResult.Failure("Could not open the dialer for $phone.")
        }
        val label = contact ?: phone
        return ToolResult.Success(
            summary = "Dialer opened for $label ($phone).",
            data = buildJsonObject {
                put("phone", phone)
                put("contact", contact ?: "")
                put("status", "dialer-opened")
            }
        )
    }
}
