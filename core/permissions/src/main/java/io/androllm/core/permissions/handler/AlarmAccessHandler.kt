package io.androllm.core.permissions.handler

import android.content.Context
import android.os.Build
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import javax.inject.Inject

/**
 * Exact Alarm / Scheduling — one-shot alarms and reminders.
 *
 * The manifest declares USE_EXACT_ALARM, which Android 13+ grants
 * automatically at install time, and SET_ALARM (a normal permission) on older
 * versions. SCHEDULE_EXACT_ALARM (the Android 12 settings gate) is not
 * declared because no feature requires exact alarms on those versions — so
 * this gate never opens a dialog or a settings screen.
 */
class AlarmAccessHandler @Inject constructor() : PermissionHandler {

    override val id = "alarms"
    override val title = "Alarms & Scheduling"
    override val description = "Set alarms and reminders."
    override val explanation = "Lets the assistant set one-shot alarms and reminders. " +
        "Exact-alarm access is granted automatically by Android 13+ — nothing is requested here."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> = emptyList()

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> PermissionState.GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PermissionState.NOT_REQUIRED
        else -> PermissionState.GRANTED
    }

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
