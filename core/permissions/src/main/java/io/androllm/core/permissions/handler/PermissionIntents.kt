package io.androllm.core.permissions.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Small intent helpers for the permission handlers — never exported. All
 * launchers go through the application context with NEW_TASK so they work
 * from any context (including the accessibility settings flow).
 */
internal object PermissionIntents {

    /** App detail page (a runtime permission can be toggled there). */
    fun appDetails(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Per-app notification settings. */
    fun appNotificationSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
