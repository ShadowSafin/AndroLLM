package io.androllm.core.permissions.handler

import android.Manifest
import android.content.Context
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import io.androllm.core.utils.PermissionUtils
import javax.inject.Inject

/**
 * Contacts — the "Message Mom" / "Call Dad" / contact lookup commands.
 */
class ContactsPermissionHandler @Inject constructor() : PermissionHandler {

    override val id = "contacts"
    override val title = "Contacts"
    override val description = "Look up people for calls and messages."
    override val explanation = "Lets the assistant find a contact when you say “Message Mom” or " +
        "“Call Dad”. Contact names are read on demand and stay on your device."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(Manifest.permission.READ_CONTACTS)

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionUtils.hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
