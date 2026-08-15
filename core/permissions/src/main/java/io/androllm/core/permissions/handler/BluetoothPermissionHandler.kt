package io.androllm.core.permissions.handler

import android.Manifest
import android.content.Context
import android.os.Build
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import javax.inject.Inject

/**
 * Bluetooth — interacting with connected Bluetooth devices.
 *
 * The manifest declares BLUETOOTH / BLUETOOTH_ADMIN (normal permissions,
 * auto-granted, pre-Android 12) but NOT BLUETOOTH_CONNECT, so on Android 12+
 * there is deliberately nothing to request: the Bluetooth tool degrades
 * gracefully instead of asking for a permission the build does not use.
 */
class BluetoothPermissionHandler @Inject constructor() : PermissionHandler {

    override val id = "bluetooth"
    override val title = "Bluetooth"
    override val description = "Interact with connected Bluetooth devices."
    override val explanation = "Used to interact with connected Bluetooth devices. On this " +
        "build the Bluetooth tool doesn't request the Android 12+ connect permission, so " +
        "nothing is asked here."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            emptyList()
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionState.NOT_REQUIRED
        } else {
            PermissionState.GRANTED
        }

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
