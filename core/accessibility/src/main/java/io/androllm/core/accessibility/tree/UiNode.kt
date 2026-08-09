package io.androllm.core.accessibility.tree

import android.graphics.Rect

/**
 * A semantic node in the UI tree — a cheap, stable mirror of an
 * [android.view.accessibility.AccessibilityNodeInfo]. It never holds a live
 * reference to the framework node (those become invalid as the UI changes);
 * instead it carries a [path] of child indices so the controller can re-locate
 * the live node when an action needs to be performed.
 */
data class UiNode(
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val resourceId: String = "",
    val type: UiElementType = UiElementType.UNKNOWN,
    /** 0.0 (guess) … 1.0 (certain) — set by [UiElementClassifier]. */
    val confidence: Float = 0f,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val bounds: Rect? = null,
    val depth: Int = 0,
    /** Child indices from the root — used to re-locate the live node. */
    val path: List<Int> = emptyList(),
    val children: List<UiNode> = emptyList()
) {

    /** The strongest human-readable label the node exposes. */
    val label: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            else -> ""
        }

    /** True when the node carries any user-visible text. */
    val hasLabel: Boolean get() = label.isNotBlank()

    /** True when the node is (roughly) on screen — bounds non-empty. */
    val onScreen: Boolean get() = bounds != null && !bounds.isEmpty

    /** All descendants including this node, pre-order. */
    fun flatten(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.flatten()) }
    }

    /** Compact single-line description used by logs and the developer mode. */
    fun describeLine(): String {
        val kind = type.displayName
        val name = label.take(48)
        val id = resourceId.substringAfterLast('/').take(24)
        return buildString {
            append('[').append(kind).append(']')
            if (name.isNotBlank()) append(" \"$name\"")
            if (id.isNotBlank()) append(" (id=$id)")
        }
    }
}
