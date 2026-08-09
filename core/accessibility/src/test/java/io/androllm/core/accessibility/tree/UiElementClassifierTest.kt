package io.androllm.core.accessibility.tree

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiElementClassifierTest {

    private fun classify(
        cls: String? = null,
        id: String? = null,
        text: String? = null,
        desc: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        scrollable: Boolean = false,
        childCount: Int = 0
    ): Pair<UiElementType, Float> = UiElementClassifier.classify(
        className = cls, resourceId = id, text = text, contentDescription = desc,
        editable = editable, clickable = clickable, checkable = checkable,
        checked = checked, scrollable = scrollable, childCount = childCount
    )

    @Test
    fun `editable node is a text field`() {
        val (type, confidence) = classify(cls = "android.widget.EditText", editable = true)
        assertThat(type).isEqualTo(UiElementType.TEXT_FIELD)
        assertThat(confidence).isEqualTo(1.0f)
    }

    @Test
    fun `button classes map to buttons`() {
        assertThat(classify(cls = "android.widget.Button").first)
            .isEqualTo(UiElementType.BUTTON)
        assertThat(classify(cls = "com.google.android.material.button.MaterialButton").first)
            .isEqualTo(UiElementType.BUTTON)
    }

    @Test
    fun `checkbox switch radio detected from classes`() {
        assertThat(classify(cls = "android.widget.CheckBox").first).isEqualTo(UiElementType.CHECKBOX)
        assertThat(classify(cls = "android.widget.Switch").first).isEqualTo(UiElementType.SWITCH)
        assertThat(classify(cls = "android.widget.RadioButton").first).isEqualTo(UiElementType.RADIO_BUTTON)
    }

    @Test
    fun `recycler view is a list`() {
        val (type, confidence) = classify(cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true)
        assertThat(type).isEqualTo(UiElementType.LIST)
        assertThat(confidence).isEqualTo(0.85f)
    }

    @Test
    fun `compose clickable maps to button via semantics`() {
        val (type, confidence) = classify(cls = "androidx.compose.ui.platform.ComposeView", clickable = true)
        assertThat(type).isEqualTo(UiElementType.BUTTON)
        assertThat(confidence).isEqualTo(0.85f)
    }

    @Test
    fun `webview and progress detected`() {
        assertThat(classify(cls = "android.webkit.WebView").first).isEqualTo(UiElementType.WEBVIEW)
        assertThat(classify(cls = "android.widget.ProgressBar").first).isEqualTo(UiElementType.PROGRESS)
    }

    @Test
    fun `clickable with label is a guessed button`() {
        val (type, confidence) = classify(text = "Search", clickable = true)
        assertThat(type).isEqualTo(UiElementType.BUTTON)
        assertThat(confidence).isEqualTo(0.5f)
    }

    @Test
    fun `label-only node is text`() {
        assertThat(classify(text = "Hello").first).isEqualTo(UiElementType.TEXT)
    }

    @Test
    fun `resource id hint can classify buttons`() {
        assertThat(classify(id = "com.app:id/send_button").first).isEqualTo(UiElementType.BUTTON)
    }
}
