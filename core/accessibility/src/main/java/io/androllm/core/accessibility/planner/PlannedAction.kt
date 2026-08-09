package io.androllm.core.accessibility.planner

/**
 * One step of a UI automation plan. Planners emit these; the executor maps
 * them onto concrete [io.androllm.core.accessibility.controller.AccessibilityController]
 * calls. [description] is a human-readable rendering used in logs, the
 * developer mode and confirmations.
 */
sealed class PlannedAction(val description: String) {

    /** Tap an element by its on-screen label. */
    class Click(val target: String, val index: Int = 0) :
        PlannedAction("tap \"$target\"")

    /** Type [text] into the focused field (or the field matching [into]). */
    class Type(val text: String, val into: String? = null) :
        PlannedAction("type text")

    class Scroll(val direction: String) : PlannedAction("scroll $direction")

    class Swipe(val direction: String) : PlannedAction("swipe $direction")

    data object Back : PlannedAction("go back")

    data object Home : PlannedAction("go home")

    data object Recents : PlannedAction("open recents")

    data object Notifications : PlannedAction("open notifications")

    data object QuickSettings : PlannedAction("open quick settings")

    /** Launch an app by its visible label. */
    class LaunchApp(val label: String) : PlannedAction("open $label")

    class Wait(val millis: Long) : PlannedAction("wait")

    /** Ask the user something (confirmations are auto-detected too). */
    class RequestConfirmation(val summary: String) : PlannedAction("ask: $summary")

    /** The goal is satisfied — stop. */
    class Done(val summary: String = "") : PlannedAction("done")
}
