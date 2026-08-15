package io.androllm.core.permissions.handler

import android.Manifest
import android.content.Context
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import javax.inject.Inject

/**
 * Location — current-location weather, nearby places, navigation.
 *
 * Deliberately NOT requested: no location-aware feature is implemented yet
 * (weather takes an explicit city, maps open navigation intents without
 * device location). [isFeatureEnabled] returns false, so the whole pipeline
 * reports [PermissionState.NOT_REQUIRED] and the setup UI shows the card as
 * "Not required" instead of asking. Wire this to true the day a feature
 * actually consumes device location — and keep background location out unless
 * a feature genuinely needs it.
 */
class LocationPermissionHandler @Inject constructor() : PermissionHandler {

    override val id = "location"
    override val title = "Location"
    override val description = "Current-location weather and nearby places."
    override val explanation = "Would let the assistant answer with your current location " +
        "(weather, nearby places, navigation). No location feature is enabled yet, so this is " +
        "never requested."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> =
        listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

    /** No implemented feature consumes device location — never ask for it. */
    override fun isFeatureEnabled(context: Context): Boolean = false

    override fun rawState(context: Context): PermissionState = PermissionState.NOT_REQUIRED

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
