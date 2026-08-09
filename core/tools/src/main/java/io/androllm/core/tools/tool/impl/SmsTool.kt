package io.androllm.core.tools.tool.impl

import android.Manifest
import android.content.Context
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.utils.PermissionUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Sends an SMS. Never fires silently: the executor always routes this tool
 * through a user confirmation first, and the tool itself double-checks the
 * SEND_SMS runtime permission (it fails with guidance when missing).
 */
@Singleton
class SmsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "send_sms",
        description = "Send an SMS text message. The recipient may be a phone number (e.g. +919876543210) OR a contact name like 'Mom' — the contact is resolved automatically. Always requires the user's confirmation before sending.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("phone") {
                    put("type", "string")
                    put("description", "Recipient phone number with country code (e.g. +919876543210) or a contact name (e.g. 'Mom')")
                }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "The message text")
                }
            }
            putJsonArray("required") { add("phone"); add("message") }
        },
        permission = ToolPermission.SMS,
        requiresConfirmation = true,
        confirmationPrompt = "send the SMS to {phone}",
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val rawRecipient = ToolArgs.str(arguments, "phone", "to", "number")
            ?: return ToolResult.Failure("Missing required argument: phone")
        val message = ToolArgs.str(arguments, "message", "text", "body")
            ?: return ToolResult.Failure("Missing required argument: message")

        if (!PermissionUtils.hasPermission(context, Manifest.permission.SEND_SMS)) {
            return ToolResult.Failure(
                "SMS permission is not granted. Enable \"Send SMS\" for AndroLLM in Android settings, then try again."
            )
        }

        // The model often writes the recipient as a contact name ("Mom") or a
        // formatted number — neither is a valid SMS address on its own, and
        // sendTextMessage would throw IllegalArgumentException, so the send
        // would silently fail right after the user approved it. Resolve the
        // recipient to a well-formed number first; fail with clear guidance
        // only when it genuinely cannot be resolved. The contacts query runs
        // off the main thread (tool calls execute on the chat's Main scope).
        val phone = if (ContactResolver.isPhoneNumber(rawRecipient)) {
            ContactResolver.normalize(rawRecipient)
        } else {
            val contactsGranted =
                PermissionUtils.hasPermission(context, Manifest.permission.READ_CONTACTS)
            val resolved = withContext(Dispatchers.IO) {
                if (contactsGranted) ContactResolver.resolveByName(context, rawRecipient) else null
            }?.let { ContactResolver.normalize(it) }
            if (resolved.isNullOrEmpty()) {
                return ToolResult.Failure(
                    if (contactsGranted) {
                        "Could not send the SMS: no contact named \"$rawRecipient\" was found. " +
                            "Please give me the recipient's phone number."
                    } else {
                        "Could not send the SMS to \"$rawRecipient\": it isn't a phone number and " +
                            "I can't look up contacts (Contacts access isn't granted). Grant it in " +
                            "Settings → Automation, or give me the number directly."
                    }
                )
            }
            resolved
        }

        return runCatching {
            val manager = SmsManager.getDefault()
            // divideMessage splits long and unicode (emoji/accents) texts into
            // correctly-encoded parts; sendMultipartTextMessage also handles
            // the single-part case, so no length branch is needed.
            manager.sendMultipartTextMessage(
                phone, null, manager.divideMessage(message), null, null
            )
            val display = if (rawRecipient == phone) phone else "$rawRecipient ($phone)"
            ToolResult.Success(
                summary = "SMS sent to $display: \"${message.take(60)}\"",
                data = buildJsonObject {
                    put("recipient", rawRecipient)
                    put("phone", phone)
                    put("message", message)
                    put("status", "sent")
                }
            )
        }.getOrElse {
            ToolResult.Failure("SMS could not be sent: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
