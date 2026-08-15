package io.androllm.core.permissions.handler

import android.content.Context
import io.androllm.core.accessibility.AccessibilityAutomationService
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import javax.inject.Inject

/**
 * Accessibility Automation — operating other apps on the user's behalf.
 *
 * Android does NOT grant accessibility through a runtime dialog: the user must
 * toggle the service in Settings → Accessibility. This handler opens exactly
 * that screen, then re-checks [AccessibilityAutomationService.isServiceEnabled]
 * when the user returns (the UI refreshes on lifecycle resume). The service is
 * never enabled programmatically.
 */
class AccessibilityAccessHandler @Inject constructor() : PermissionHandler {

    override val id = "accessibility"
    override val title = "Accessibility Automation"
    override val description = "Let AndroLLM operate apps for you."
    override val explanation = "Accessibility access allows AndroLLM to interact with apps on " +
        "your behalf for automation — opening apps, tapping controls, entering text, scrolling " +
        "and completing multi-step tasks. You can disable it at any time in system settings."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = true

    override fun runtimePermissions(context: Context): List<String> = emptyList()

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (AccessibilityAutomationService.isServiceEnabled(context)) PermissionState.GRANTED
        else PermissionState.NEEDS_SETTINGS

    override fun openSettings(context: Context): Boolean =
        AccessibilityAutomationService.openSettings(context)
}
