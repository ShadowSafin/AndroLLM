package io.androllm.core.accessibility.runtime

import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.runtime.Runtime
import io.androllm.core.runtime.RuntimeCategory
import io.androllm.core.runtime.RuntimeStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the UI automation runtime (accessibility-service gestures,
 * reading, typing, multi-step tasks, QR scanning) into the central
 * [io.androllm.core.runtime.RuntimeRegistry]. Only observes the shared
 * [AccessibilityController] binding — it never binds or unbinds the service.
 */
@Singleton
class AutomationRuntime @Inject constructor(
    private val controller: AccessibilityController
) : Runtime {

    override val id = "automation"
    override val displayName = "UI Automation"
    override val category = RuntimeCategory.AUTOMATION
    override val description = "Accessibility-service UI automation: gestures, screen reading, typing, multi-step tasks, QR scanning."

    override suspend fun status(): RuntimeStatus = runCatching {
        val connected = controller.serviceOrNull() != null
        if (connected) {
            RuntimeStatus(true, "Service connected")
        } else {
            RuntimeStatus(
                available = false,
                summary = "Service not connected",
                detail = "Enable the 'AndroLLM Automation' service in Accessibility settings to use UI automation."
            )
        }
    }.getOrElse { e ->
        RuntimeStatus(false, "Status check failed", e.message ?: e.javaClass.simpleName)
    }
}
