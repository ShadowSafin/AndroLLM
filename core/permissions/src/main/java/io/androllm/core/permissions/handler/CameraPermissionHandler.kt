package io.androllm.core.permissions.handler

import android.Manifest
import android.content.Context
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import io.androllm.core.utils.PermissionUtils
import javax.inject.Inject

/**
 * Camera — QR scanning and the flashlight toggle (the flashlight runs
 * through the camera unit, so it shares this gate).
 */
class CameraPermissionHandler @Inject constructor() : PermissionHandler {

    override val id = "camera"
    override val title = "Camera"
    override val description = "QR scanning and flashlight."
    override val explanation = "The camera is used to scan QR codes and to control the " +
        "flashlight. Nothing is recorded unless you start a scan."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(Manifest.permission.CAMERA)

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionUtils.hasCameraPermission(context)) PermissionState.GRANTED
        else PermissionState.DENIED

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
