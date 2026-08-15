package io.androllm.core.permissions

/**
 * User-facing features that can require Android permissions/access.
 *
 * The app derives "what to ask for" from the features that are actually
 * enabled — never from a hardcoded request-everything list. Each feature maps
 * to one or more [PermissionHandler] ids; [PermissionManager.permissionsNeeded]
 * flattens that mapping into the concrete permission strings.
 */
enum class Feature(val displayName: String) {
    VOICE_ASSISTANT("Voice Assistant"),
    NOTIFICATIONS("Notifications"),
    ACCESSIBILITY_AUTOMATION("Accessibility Automation"),
    WEATHER_LOCATION("Location-aware weather"),
    SMS("SMS"),
    CONTACTS("Contacts"),
    CAMERA("Camera"),
    CALENDAR("Calendar"),
    BLUETOOTH("Bluetooth"),
    ALARMS("Alarms & Scheduling");

    /** Handler ids backing this feature, in the recommended request order. */
    val handlerIds: List<String>
        get() = when (this) {
            VOICE_ASSISTANT -> listOf("voice_assistant")
            NOTIFICATIONS -> listOf("notifications")
            ACCESSIBILITY_AUTOMATION -> listOf("accessibility")
            WEATHER_LOCATION -> listOf("location")
            SMS -> listOf("sms")
            CONTACTS -> listOf("contacts")
            CAMERA -> listOf("camera")
            CALENDAR -> listOf("calendar")
            BLUETOOTH -> listOf("bluetooth")
            ALARMS -> listOf("alarms")
        }
}
