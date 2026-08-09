package io.androllm.core.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.androllm.core.common.AppConstants

/**
 * Helpers for runtime permission handling.
 */
object PermissionUtils {

    /**
     * Checks whether the given permission has been granted.
     */
    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether all given permissions have been granted.
     */
    fun hasAllPermissions(context: Context, permissions: List<String>): Boolean =
        permissions.all { hasPermission(context, it) }

    /**
     * Requests the given permissions from the activity.
     */
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        val missing = permissions.filterNot { hasPermission(activity, it) }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }

    /**
     * Checks whether storage permissions are granted.
     */
    fun hasStoragePermission(context: Context): Boolean =
        hasPermission(context, AppConstants.Permissions.READ_EXTERNAL_STORAGE)

    /**
     * Checks whether notifications permission is granted (Android 13+).
     */
    fun hasNotificationPermission(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    /**
     * Checks whether the microphone permission is granted.
     */
    fun hasRecordAudioPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.RECORD_AUDIO)

    /**
     * Checks whether the camera permission is granted.
     */
    fun hasCameraPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.CAMERA)
}

/**
 * Notification-related permission checks (Android 13+ POST_NOTIFICATIONS).
 */
object NotificationPermissions {

    /** True when this app may post notifications on this device. */
    fun canNotify(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            PermissionUtils.hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
}
