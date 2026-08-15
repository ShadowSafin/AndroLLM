package io.androllm.feature.setup

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionManager
import io.androllm.core.permissions.PermissionState
import javax.inject.Inject

/**
 * Bridges [PermissionManager] to Settings → Permissions & Access. The same
 * handlers (and therefore the same live states) shown during first-launch
 * setup are manageable here at any time.
 */
@HiltViewModel
class PermissionsAccessViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PermissionManager
) : ViewModel() {

    val handlers: List<PermissionHandler>
        get() = permissionManager.handlers

    fun status(handler: PermissionHandler, activity: Activity? = null): PermissionState =
        permissionManager.status(handler, activity)

    fun runtimePermissions(handler: PermissionHandler): List<String> =
        permissionManager.runtimePermissions(handler)

    fun onRequested(handler: PermissionHandler) = permissionManager.onPermissionRequested(handler)

    fun openSettings(handler: PermissionHandler): Boolean = permissionManager.openSettings(handler)
}
