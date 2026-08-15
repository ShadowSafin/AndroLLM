package io.androllm.core.permissions

/**
 * Live state of one permission / access gate, shown on the first-launch setup
 * screen and in Settings → Permissions & Access.
 *
 * The "optional / required / not required" framing lives on the
 * [PermissionHandler] (see [PermissionHandler.isRequired] and
 * [PermissionHandler.isOptional]); this enum is the *live* grant state.
 */
enum class PermissionState {

    /** The feature behind this gate is not enabled/implemented — never requested. */
    NOT_REQUIRED,

    /** Granted (or not applicable on this device, e.g. notifications pre-13). */
    GRANTED,

    /** Declined in the system dialog — the feature is off, a retry is possible. */
    DENIED,

    /** Declined twice / "don't ask again" — the system dialog no longer appears. */
    PERMANENTLY_DENIED,

    /** Special access that can only be granted from a system settings screen. */
    NEEDS_SETTINGS,

    /** Not available on this device / Android version. */
    UNAVAILABLE;

    /** Short human label used by the setup and settings UI. */
    fun label(): String = when (this) {
        NOT_REQUIRED -> "Not required"
        GRANTED -> "Granted"
        DENIED -> "Not enabled"
        PERMANENTLY_DENIED -> "Permission blocked"
        NEEDS_SETTINGS -> "Needs settings"
        UNAVAILABLE -> "Unavailable"
    }
}
