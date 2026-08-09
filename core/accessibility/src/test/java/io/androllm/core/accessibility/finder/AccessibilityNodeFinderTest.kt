package io.androllm.core.accessibility.finder

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.tree.UiNode
import org.junit.Test

class AccessibilityNodeFinderTest {

    private fun node(
        text: String = "",
        desc: String = "",
        type: UiElementType = UiElementType.TEXT,
        editable: Boolean = false,
        scrollable: Boolean = false,
        path: List<Int> = emptyList(),
        children: List<UiNode> = emptyList()
    ) = UiNode(
        text = text,
        contentDescription = desc,
        type = type,
        editable = editable,
        scrollable = scrollable,
        path = path,
        children = children,
        bounds = Rect(0, 0, 100, 100)
    )

    private fun tree(): UiNode {
        val searchField = node(text = "Search", type = UiElementType.TEXT_FIELD, editable = true, path = listOf(0, 0))
        val sendButton = node(text = "Send", type = UiElementType.BUTTON, path = listOf(0, 1))
        val scrollableList = node(
            type = UiElementType.LIST,
            scrollable = true,
            path = listOf(1),
            children = listOf(
                node(text = "Mom", path = listOf(1, 0)),
                node(text = "Dad", path = listOf(1, 1))
            )
        )
        return node(
            type = UiElementType.UNKNOWN,
            children = listOf(
                node(children = listOf(searchField, sendButton)),
                scrollableList
            )
        )
    }

    @Test
    fun `finds by exact text`() {
        val root = tree()
        assertThat(AccessibilityNodeFinder.findOne(root, UiSelector(text = "Mom"))?.text).isEqualTo("Mom")
    }

    @Test
    fun `finds by substring`() {
        val root = tree()
        val hits = AccessibilityNodeFinder.find(root, UiSelector(textContains = "en"))
        assertThat(hits.map { it.text }).containsExactly("Send")
    }

    @Test
    fun `finds editable field`() {
        assertThat(AccessibilityNodeFinder.findEditable(tree())?.text).isEqualTo("Search")
    }

    @Test
    fun `selector index picks later matches`() {
        val root = node(children = listOf(
            node(text = "Retry", type = UiElementType.BUTTON, path = listOf(0)),
            node(text = "Retry", type = UiElementType.BUTTON, path = listOf(1))
        ))
        val second = AccessibilityNodeFinder.findOne(root, UiSelector(textContains = "Retry", index = 1))
        assertThat(second?.path).isEqualTo(listOf(1))
    }

    @Test
    fun `focused node found`() {
        val focused = node(text = "Type here", type = UiElementType.TEXT_FIELD, editable = true).copy(focused = true)
        val root = node(children = listOf(focused, node(text = "Other")))
        assertThat(AccessibilityNodeFinder.findFocused(root)?.text).isEqualTo("Type here")
    }
}
