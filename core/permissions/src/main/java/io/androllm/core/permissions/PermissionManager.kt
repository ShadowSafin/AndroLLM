package io.androllm.core.permissions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central permission/access manager — the single source of truth for every
 * runtime permission and special access the app can ask for.
 *
 * Responsibilities (per the product spec):
 *  - [status] — live state of a gate, including permanently-denied detection
 *  - [runtimePermissions] — what to feed the system permission dialog
 *  - [onPermissionRequested] — request history so permanent denial is detected
 *  - [openSettings] — special access that lives in system settings
 *  - [permissionsNeeded] / [permissionsNeededForEnabledFeatures] — derive the
 *    concrete permission list from enabled features (declarative feature map)
 *
 * Handlers are registered through Hilt multibinding ([Set] of
 * [PermissionHandler]), so the app never hardcodes a permission list.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    handlers: Set<@JvmSuppressWildcards PermissionHandler>
) {

    /** All registered handlers, in the recommended request order. */
    val handlers: List<PermissionHandler> =
        handlers.sortedBy { handler ->
            REQUEST_ORDER.indexOf(handler.id).let { if (it < 0) Int.MAX_VALUE else it }
        }

    /** Permissions the user has already been asked about this session. */
    private val requestedPermissions = Collections.synchronizedSet(mutableSetOf<String>())

    /** Lookup by [PermissionHandler.id]. */
    fun handler(id: String): PermissionHandler? = handlers.firstOrNull { it.id == id }

    /**
     * Live state of [handler]. Passing the hosting [Activity] enables
     * permanent-denial detection: once a runtime permission was requested and
     * Android reports the rationale can no longer be shown
     * (shouldShowRequestPermissionRationale == false), the state becomes
     * [PermissionState.PERMANENTLY_DENIED] and the UI offers "Open Settings"
     * instead of "Try Again".
     */
    fun status(
        handler: PermissionHandler,
        activity: Activity? = null
    ): PermissionState {
        if (!handler.isFeatureEnabled(context)) return PermissionState.NOT_REQUIRED
        val raw = handler.rawState(context)
        if (raw != PermissionState.DENIED) return raw

        // Runtime-permission denial — distinguish "try again" from "blocked".
        // Direct framework check (same call ContextCompat delegates to) so the
        // logic stays JVM-testable and never pulls android.* stubs in.
        val denied = handler.runtimePermissions(context)
            .firstOrNull { !isGranted(it) }
            ?: return PermissionState.GRANTED
        val requestedBefore = denied in requestedPermissions
        val canShowRationale = activity?.let { a ->
            runCatching { ActivityCompat.shouldShowRequestPermissionRationale(a, denied) }
                .getOrDefault(true)
        } ?: true
        return if (requestedBefore && !canShowRationale) {
            PermissionState.PERMANENTLY_DENIED
        } else {
            PermissionState.DENIED
        }
    }

    /** The dialog permissions to request for [handler] (empty = none needed). */
    fun runtimePermissions(handler: PermissionHandler): List<String> =
        handler.runtimePermissions(context)

    /**
     * Records a dialog request so permanent-denial detection knows the
     * history. Call immediately before launching the system dialog.
     */
    fun onPermissionRequested(handler: PermissionHandler) {
        requestedPermissions.addAll(handler.runtimePermissions(context))
    }

    /** Opens the system screen where [handler] can be granted. */
    fun openSettings(handler: PermissionHandler): Boolean = handler.openSettings(context)

    /** Permissions the app would need if [feature] were used on this device. */
    fun permissionsNeeded(feature: Feature): List<String> =
        feature.handlerIds.mapNotNull { handler(it) }
            .flatMap { it.runtimePermissions(context) }
            .distinct()

    /**
     * Permissions for every feature that is actually enabled on this device —
     * the "which permissions are needed from enabled features" answer.
     */
    fun permissionsNeededForEnabledFeatures(): List<String> =
        Feature.entries
            .filter { feature ->
                feature.handlerIds.any { id -> handler(id)?.isFeatureEnabled(context) == true }
            }
            .flatMap { permissionsNeeded(it) }
            .distinct()

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Recommended request order (the product spec's smart sequence):
         * explain setup → voice → notifications → accessibility → the rest.
         */
        private val REQUEST_ORDER = listOf(
            "voice_assistant",
            "notifications",
            "accessibility",
            "contacts",
            "sms",
            "calendar",
            "camera",
            "location",
            "bluetooth",
            "alarms"
        )
    }
}
