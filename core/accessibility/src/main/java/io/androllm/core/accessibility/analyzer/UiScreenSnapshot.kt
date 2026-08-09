package io.androllm.core.accessibility.analyzer

import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.tree.UiNode

/**
 * A read-only snapshot of the current screen: the semantic tree plus the
 * context the planners and the LLM need (current app, focused/selected text,
 * loading state). The whole thing is plain data — serializable into a prompt
 * or into the developer-mode log.
 */
data class UiScreenSnapshot(
    val packageName: String = "",
    val windowTitle: String = "",
    val root: UiNode? = null,
    val focusedText: String = "",
    val selectedText: String = "",
    val loading: Boolean = false,
    val ocrLines: List<String> = emptyList(),
    val capturedAt: Long = System.currentTimeMillis()
) {

    val nodes: List<UiNode> get() = root?.flatten()?.toList() ?: emptyList()

    val textFields: List<UiNode> get() = nodes.filter { it.type == UiElementType.TEXT_FIELD }
    val buttons: List<UiNode> get() = nodes.filter { it.type == UiElementType.BUTTON }
    val dialogs: List<UiNode> get() = nodes.filter {
        it.type == UiElementType.DIALOG || it.type == UiElementType.BOTTOM_SHEET
    }

    /** No usable elements at all (empty screen / splash / transition). */
    val isEmpty: Boolean get() = root == null || nodes.none { it.hasLabel || it.editable }

    /**
     * Compact text form fed to the planners / LLM. Bounded so a huge tree can
     * never blow the model's context.
     */
    fun describe(maxLines: Int = 80): String {
        val sb = StringBuilder()
        if (packageName.isNotBlank()) sb.append("App: ").append(packageName)
        if (windowTitle.isNotBlank()) sb.append(" — ").append(windowTitle)
        sb.append('\n')
        if (focusedText.isNotBlank()) sb.append("Focused: \"").append(focusedText.take(60)).append("\"\n")
        if (selectedText.isNotBlank()) sb.append("Selected: \"").append(selectedText.take(60)).append("\"\n")
        if (loading) sb.append("Loading: true\n")

        var count = 0
        for (node in nodes) {
            if (count >= maxLines) break
            val line = node.describeLine()
            if (line.isBlank()) continue
            sb.append("  ").append(line).append('\n')
            count++
        }
        if (ocrLines.isNotEmpty()) {
            sb.append("OCR text:\n")
            ocrLines.take(12).forEach { sb.append("  ocr: ").append(it.take(100)).append('\n') }
        }
        return sb.toString()
    }
}
