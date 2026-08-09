package io.androllm.core.tools.tool.impl

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.utils.PermissionUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Looks up a contact by name and returns their phone numbers and emails —
 * useful before sending an SMS, calling, or emailing ("message Mom").
 */
@Singleton
class ContactTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "find_contacts",
        description = "Look up a contact by name and return their phone numbers and email addresses. Use before messaging or calling a named person.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Contact name or part of it")
                }
            }
            putJsonArray("required") { add("name") }
        },
        permission = ToolPermission.CONTACTS,
        category = ToolCategory.COMMUNICATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val name = ToolArgs.str(arguments, "name", "query", "contact")
            ?: return ToolResult.Failure("Missing required argument: name")
        if (!PermissionUtils.hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return ToolResult.Failure("Contacts permission is not granted — enable it in Android settings.")
        }
        return runCatching {
            val lookup = "%$name%"
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE",
                arrayOf(lookup),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ) ?: return ToolResult.Failure("No contact data available.")

            val contacts = linkedMapOf<Long, MutableList<String>>()
            val displayNames = linkedMapOf<Long, String>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(3)
                    val display = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    displayNames[id] = display
                    contacts.getOrPut(id) { mutableListOf() }.add(number)
                }
            }
            if (contacts.isEmpty()) {
                return ToolResult.Failure("No contacts matched \"$name\".")
            }
            val sb = StringBuilder("Contacts matching \"$name\":")
            val items = mutableListOf<JsonObject>()
            contacts.entries.take(3).forEach { (id, numbers) ->
                val display = displayNames[id] ?: "Contact"
                items += buildJsonObject {
                    put("name", display)
                    putJsonArray("phones") { numbers.take(2).forEach { add(it) } }
                }
                sb.append(' ').append(display).append(": ").append(numbers.first())
            }
            val data = buildJsonObject { putJsonArray("contacts") { items.forEach { add(it) } } }
            ToolResult.Success(summary = sb.toString(), data = data)
        }.getOrElse {
            ToolResult.Failure("Contact lookup failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
