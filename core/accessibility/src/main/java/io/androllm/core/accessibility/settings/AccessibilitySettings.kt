package io.androllm.core.accessibility.settings

/**
 * User-configurable behaviour of the accessibility automation engine. The
 * master switch lives in the Android system (Settings → Accessibility → the
 * service); everything here tunes how aggressively the engine acts.
 */
data class AccessibilitySettings(
    /** Scroll list views until the target element is visible before tapping. */
    val autoScrollIntoView: Boolean = true,
    /** Use the loaded local GGUF model to pick the next UI action when possible. */
    val llmPlanning: Boolean = true,
    /** Ask for confirmation before high-risk steps (sending, paying, deleting…). */
    val confirmHighRisk: Boolean = true,
    /** Hard cap on planning steps per task (loop guard). */
    val maxSteps: Int = 12,
    /** Developer mode: record full node dumps, execution trees and gesture logs. */
    val developerMode: Boolean = false,
    /** Merge on-device OCR text into screen analysis (requires an OCR engine). */
    val ocrEnabled: Boolean = false,
    /** Keep the persistent "UI Automation active" notification visible. */
    val showStatusNotification: Boolean = true
)
