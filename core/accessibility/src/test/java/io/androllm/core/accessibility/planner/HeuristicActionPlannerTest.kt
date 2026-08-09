package io.androllm.core.accessibility.planner

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.tree.UiNode
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HeuristicActionPlannerTest {

    private val planner = HeuristicActionPlanner()

    private fun node(
        text: String = "",
        type: UiElementType = UiElementType.TEXT,
        editable: Boolean = false,
        children: List<UiNode> = emptyList()
    ) = UiNode(
        text = text,
        type = type,
        editable = editable,
        children = children,
        bounds = Rect(0, 0, 100, 100)
    )

    private fun screen(
        packageName: String = "com.whatsapp",
        title: String = "WhatsApp",
        buttons: List<UiNode> = emptyList(),
        fields: List<UiNode> = emptyList(),
        texts: List<UiNode> = emptyList(),
        dialogs: List<UiNode> = emptyList()
    ): UiScreenSnapshot = UiScreenSnapshot(
        packageName = packageName,
        windowTitle = title,
        root = node(children = buttons + fields + texts + dialogs)
    )

    @Test
    fun `launches the requested app first`() = runTest {
        val s = screen(packageName = "com.android.launcher")
        val action = planner.nextAction("Open WhatsApp and send a message", s, emptyList())
        assertThat(action).isInstanceOf(PlannedAction.LaunchApp::class.java)
        assertThat((action as PlannedAction.LaunchApp).label).isEqualTo("WhatsApp")
    }

    @Test
    fun `does not relaunch the app already on screen`() = runTest {
        val s = screen()
        val action = planner.nextAction("Open WhatsApp and send a message", s, emptyList())
        assertThat(action).isNotInstanceOf(PlannedAction.LaunchApp::class.java)
    }

    @Test
    fun `waits after launching`() = runTest {
        val s = screen(packageName = "com.whatsapp")
        val action = planner.nextAction("Open WhatsApp", s, listOf(PlannedAction.LaunchApp("WhatsApp")))
        assertThat(action).isInstanceOf(PlannedAction.Wait::class.java)
    }

    @Test
    fun `types the search query into a field`() = runTest {
        val s = screen(fields = listOf(node("Search", UiElementType.TEXT_FIELD, editable = true)))
        val action = planner.nextAction("Search YouTube for Android 17", s, emptyList())
        assertThat(action).isInstanceOf(PlannedAction.Type::class.java)
        assertThat((action as PlannedAction.Type).text).isEqualTo("Android 17")
    }

    @Test
    fun `presses search after typing a query`() = runTest {
        val s = screen(buttons = listOf(node("Search", UiElementType.BUTTON)))
        val history = listOf(PlannedAction.Type("Android 17"))
        val action = planner.nextAction("Search YouTube for Android 17", s, history)
        assertThat(action).isInstanceOf(PlannedAction.Click::class.java)
        assertThat((action as PlannedAction.Click).target).isEqualTo("Search")
    }

    @Test
    fun `extracts the message from saying clause`() = runTest {
        val s = screen(fields = listOf(node("Type a message", UiElementType.TEXT_FIELD, editable = true)))
        val action = planner.nextAction("Send a WhatsApp message to Mom saying I'll be late", s, emptyList())
        assertThat(action).isInstanceOf(PlannedAction.Type::class.java)
        assertThat((action as PlannedAction.Type).text).isEqualTo("I'll be late")
    }

    @Test
    fun `presses send after typing a message`() = runTest {
        val s = screen(buttons = listOf(node("Send", UiElementType.BUTTON)))
        val history = listOf(PlannedAction.Type("I'll be late"))
        val action = planner.nextAction("Tell Mom I'll be late", s, history)
        assertThat(action).isInstanceOf(PlannedAction.Click::class.java)
        assertThat((action as PlannedAction.Click).target).isEqualTo("Send")
    }

    @Test
    fun `accepts benign dialogs`() = runTest {
        val dialog = UiNode(
            type = UiElementType.DIALOG,
            children = listOf(node("Allow", UiElementType.BUTTON))
        )
        val s = screen(dialogs = listOf(dialog))
        val action = planner.nextAction("Open WhatsApp", s, emptyList())
        assertThat(action).isInstanceOf(PlannedAction.Click::class.java)
        assertThat((action as PlannedAction.Click).target).isEqualTo("Allow")
    }

    @Test
    fun `returns done when nothing actionable`() = runTest {
        val s = screen(texts = listOf(node("Nothing here")))
        val action = planner.nextAction("Just reading", s, emptyList())
        assertThat(action).isInstanceOf(PlannedAction.Done::class.java)
    }
}
