package io.androllm.core.accessibility.tree

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Builds a bounded, semantic [UiNode] tree from a live accessibility root.
 *
 * Every `AccessibilityNodeInfo` read must happen on the main thread — callers
 * route through the controller. The tree is deliberately capped
 * ([MAX_NODES]/[MAX_DEPTH]) so pathological screens (huge RecyclerViews,
 * web pages) can never blow up memory; the planner only ever sees a window
 * into the screen.
 */
object UiTreeBuilder {

    const val MAX_NODES = 300
    const val MAX_DEPTH = 24

    /** Max characters kept per text property (planner context stays small). */
    private const val MAX_TEXT = 140

    fun build(root: AccessibilityNodeInfo): UiNode? {
        var budget = MAX_NODES

        fun walk(node: AccessibilityNodeInfo, depth: Int, path: List<Int>): UiNode? {
            if (budget <= 0) return null
            if (depth > MAX_DEPTH) return null
            // Skip invisible / off-screen containers early — they never reach
            // the planner and only waste traversal budget.
            if (!node.isVisibleToUser) return null

            val (type, confidence) = UiElementClassifier.classify(
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

            val children = mutableListOf<UiNode>()
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                budget--
                walk(child, depth + 1, path + i)?.let { children += it }
                if (budget <= 0) break
            }

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            return UiNode(
                text = node.text?.toString().orEmpty().take(MAX_TEXT).trim(),
                contentDescription = node.contentDescription?.toString().orEmpty().take(MAX_TEXT).trim(),
                className = node.className?.toString().orEmpty(),
                resourceId = node.viewIdResourceName.orEmpty(),
                type = type,
                confidence = confidence,
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                focused = node.isFocused,
                selected = node.isSelected,
                bounds = bounds,
                depth = depth,
                path = path,
                children = children
            )
        }

        return walk(root, 0, emptyList())
    }
}
