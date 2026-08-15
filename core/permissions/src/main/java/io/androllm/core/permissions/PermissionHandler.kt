package io.androllm.core.permissions

import android.content.Context

/**
 * A single permission or access gate — one card on the first-launch setup
 * screen and one row in Settings → Permissions & Access.
 *
 * Implementations are thin and own the *what* (permission strings, feature
 * gating, the system settings screen to open); [PermissionManager] owns
 * ordering, live-state computation and request bookkeeping, so no permission
 * logic ever lives inside the onboarding UI.
 */
interface PermissionHandler {

    /** Stable id used by the UI and the [Feature] map (e.g. "voice_assistant"). */
    val id: String

    /** Card title, e.g. "Voice Assistant". */
    val title: String

    /** One-line blurb of what this enables. */
    val description: String

    /** Why AndroLLM needs this — always shown, never a bare permission name. */
    val explanation: String

    /** True when the permission gates the core experience. */
    val isRequired: Boolean

    /** True when the app stays fully usable without it. */
    val isOptional: Boolean

    /**
     * True when Android grants this only from a system settings screen
     * (accessibility, notification settings) — never through a dialog.
     */
    val needsSettingsScreen: Boolean

    /**
     * Android runtime permissions to request through the system dialog, if
     * any. Empty for special access ([needsSettingsScreen]) and for gates
     * that are auto-granted (exact alarms on Android 13+).
     */
    fun runtimePermissions(context: Context): List<String>

    /** Whether the feature behind this gate is enabled/implemented at all. */
    fun isFeatureEnabled(context: Context): Boolean

    /**
     * Raw grant state without request-history knowledge. [PermissionManager]
     * refines [PermissionState.DENIED] into
     * [PermissionState.PERMANENTLY_DENIED] when the dialog will no longer
     * show.
     */
    fun rawState(context: Context): PermissionState

    /** Opens the system screen where this gate can be granted. */
    fun openSettings(context: Context): Boolean
}
