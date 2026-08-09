package io.androllm.core.accessibility.finder

import android.view.accessibility.AccessibilityNodeInfo
import io.androllm.core.accessibility.tree.UiElementClassifier

/**
 * Traversal + matching over the LIVE accessibility tree. Runs on the main
 * thread only (every caller funnels through [io.androllm.core.accessibility.
 * util.MainThread]). Matching rules mirror [AccessibilityNodeFinder] so the
 * planner's view of the screen and the executor's targets stay in sync.
 */
object LiveTree {

    /** Pre-order list of up to [max] nodes. */
    fun collect(root: AccessibilityNodeInfo, max: Int = 500): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(64)
        fun walk(node: AccessibilityNodeInfo) {
            if (out.size >= max) return
            out.add(node)
            val count = node.childCount
            for (i in 0 until count) {
                if (out.size >= max) return
                walk(node.getChild(i) ?: continue)
            }
        }
        walk(root)
        return out
    }

    /** First node matching [selector] (honouring [UiSelector.index]). */
    fun find(root: AccessibilityNodeInfo, selector: UiSelector): AccessibilityNodeInfo? {
        var seen = 0
        for (node in collect(root)) {
            if (matches(node, selector)) {
                if (seen == selector.index) return node
                seen++
            }
        }
        return null
    }

    /** First visible editable node — target for typing when nothing focused. */
    fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        collect(root).firstOrNull { it.isEditable && it.isVisibleToUser }

    /** First visible scrollable node. */
    fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        collect(root).firstOrNull { it.isScrollable && it.isVisibleToUser }

    /** The focused node (input focus preferred, else view focus). */
    fun findFocused(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    fun labelOf(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()
            ?: ""

    private fun matches(node: AccessibilityNodeInfo, selector: UiSelector): Boolean {
        selector.text?.let { exact ->
            if (!labelOf(node).equals(exact, ignoreCase = true)) return false
        }
        selector.textContains?.let { contains ->
            if (!labelOf(node).contains(contains, ignoreCase = true)) return false
        }
        selector.resourceId?.let { id ->
            if (!node.viewIdResourceName.orEmpty().contains(id, ignoreCase = true)) return false
        }
        selector.type?.let { type ->
            val (t, _) = UiElementClassifier.classify(
                className = node.className?.toString(),
                resourceId = node.viewIdResourceName,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                editable = node.isEditable,
                clickable = node.isClickable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                scrollable = node.isScrollable,
                childCount = node.childCount
            )
            if (t != type) return false
        }
        return true
    }
}
