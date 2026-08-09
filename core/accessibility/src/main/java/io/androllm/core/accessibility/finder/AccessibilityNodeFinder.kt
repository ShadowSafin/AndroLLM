package io.androllm.core.accessibility.finder

import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.tree.UiNode

/**
 * A selector describing which live node an action should target. Pure — the
 * executor re-resolves it against the current tree on every action, so the
 * selector survives screen changes between planning and execution.
 */
data class UiSelector(
    /** Exact (case-insensitive) match against text OR content-description. */
    val text: String? = null,
    /** Case-insensitive substring match against text OR content-description. */
    val textContains: String? = null,
    /** Substring match against the view resource id ("…/id/send_button"). */
    val resourceId: String? = null,
    val type: UiElementType? = null,
    /** Which match to use when several nodes satisfy the selector. */
    val index: Int = 0
) {
    val summary: String
        get() = buildString {
            text?.let { append("text=\"$it\" ") }
            textContains?.let { append("contains=\"$it\" ") }
            resourceId?.let { append("id=*$it ") }
            type?.let { append("type=${it.displayName} ") }
            if (index > 0) append("[$index]")
        }.trim()
}

/**
 * Pure node finder over the semantic [UiNode] tree. Used by the screen
 * analyzer, the planners and the controller's click/type resolution — the
 * exact same matching rules apply everywhere, so what the LLM sees is what
 * gets tapped.
 */
object AccessibilityNodeFinder {

    /** All nodes matching [selector], in tree order. */
    fun find(root: UiNode, selector: UiSelector): List<UiNode> =
        root.flatten().filter { matches(it, selector) }.toList()

    /** The [selector.index]-th match, or null. */
    fun findOne(root: UiNode, selector: UiSelector): UiNode? {
        var seen = 0
        for (node in root.flatten()) {
            if (matches(node, selector)) {
                if (seen == selector.index) return node
                seen++
            }
        }
        return null
    }

    /** First editable node (text field) — target for typing when none focused. */
    fun findEditable(root: UiNode): UiNode? =
        root.flatten().firstOrNull { it.type == UiElementType.TEXT_FIELD && it.enabled }

    /** The focused node, if any. */
    fun findFocused(root: UiNode): UiNode? =
        root.flatten().firstOrNull { it.focused }

    private fun matches(node: UiNode, selector: UiSelector): Boolean {
        selector.text?.let { exact ->
            val n = node.label
            if (!n.equals(exact, ignoreCase = true)) return false
        }
        selector.textContains?.let { contains ->
            val n = node.label
            if (!n.contains(contains, ignoreCase = true)) return false
        }
        selector.resourceId?.let { id ->
            if (!node.resourceId.contains(id, ignoreCase = true)) return false
        }
        selector.type?.let { type ->
            if (node.type != type) return false
        }
        return true
    }
}
