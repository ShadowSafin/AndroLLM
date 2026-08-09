package io.androllm.core.tools.tool.impl

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import io.androllm.core.utils.PermissionUtils

/**
 * Resolves a recipient argument the model tends to write in human form — a
 * contact name ("Mom"), a formatted number ("(555) 123-4567") or a plain
 * number — into a well-formed phone address that Android's telephony APIs
 * accept. `SmsManager.sendTextMessage` throws `IllegalArgumentException` for
 * anything that isn't a valid SMS address, so a name passed straight through
 * would make the send fail silently after the user approved the action.
 */
object ContactResolver {

    /**
     * True when [raw] already looks like a dialable phone number. Requires
     * the digits to dominate the alphanumeric content so a name that happens
     * to contain digits ("Room 404") is never mistaken for a number.
     */
    fun isPhoneNumber(raw: String): Boolean {
        val normalized = normalize(raw)
        if (normalized.length < 3 || normalized.any { !it.isDigit() && it != '+' }) return false
        val alphanumeric = raw.count { it.isLetterOrDigit() }
        val digits = raw.count { it.isDigit() }
        return digits * 2 >= alphanumeric
    }

    /**
     * Strips formatting and keeps the country prefix:
     * "(555) 123-4567" → "5551234567", "+1 (555) 123-4567" → "+15551234567".
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return (if (plus) "+" else "") + digits
    }

    /**
     * Looks up [name] in the contacts provider and returns the first phone
     * number, or null when the lookup cannot run (READ_CONTACTS missing) or
     * nothing matches.
     */
    fun resolveByName(context: Context, name: String): String? {
        if (!PermissionUtils.hasPermission(context, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE",
                arrayOf("%$name%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                if (c.moveToFirst()) c.getString(1) else null
            }
        }.getOrNull()
    }
}
