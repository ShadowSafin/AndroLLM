package io.androllm.core.accessibility.planner

/**
 * Pure keyword classifier that decides whether an automation step is
 * high-risk and must be confirmed first. Mirrors the confirmation rules of the
 * tool framework: sending, paying, booking, deleting, installing, account or
 * system changes always require explicit approval — everything else executes
 * immediately.
 */
object SafetyClassifier {

    private val RISKY_GOAL_WORDS = listOf(
        "send", "pay", "payment", "buy", "purchase", "book", "booking", "order",
        "place order", "confirm", "delete", "remove", "install", "uninstall",
        "transfer", "subscribe", "subscription", "charge", "checkout",
        "withdraw", "change password", "reset", "cancel subscription", "sign up"
    )

    private val RISKY_BUTTON_WORDS = listOf(
        "send", "pay", "buy", "purchase", "book", "order", "place order",
        "confirm", "checkout", "delete", "remove", "install", "transfer",
        "subscribe", "charge", "withdraw", "submit", "request", "reserve",
        "place", "checkout"
    )

    fun isRiskyGoal(goal: String): Boolean {
        val g = goal.lowercase()
        return RISKY_GOAL_WORDS.any { g.contains(it) }
    }

    /** True when the tapped element's label suggests a risky action. */
    fun isRiskyTarget(target: String): Boolean {
        val t = target.lowercase().trim()
        if (t.isEmpty() || t.length > 48) return false
        return RISKY_BUTTON_WORDS.any { t == it || t.contains(it) }
    }

    /**
     * Confirmation decision for an emitted [action]: risky buttons always
     * confirm; a risky goal + any "send/confirm"-labelled tap confirms too.
     */
    fun requiresConfirmation(goal: String, action: PlannedAction): Boolean {
        if (action !is PlannedAction.Click) return false
        if (isRiskyTarget(action.target)) return true
        // Risky goal + the tapped button is part of that risky flow ("Book an
        // Uber" → tapping "Request ride") → confirm.
        val t = action.target.lowercase()
        return isRiskyGoal(goal) && RISKY_BUTTON_WORDS.any { t.contains(it) }
    }
}
