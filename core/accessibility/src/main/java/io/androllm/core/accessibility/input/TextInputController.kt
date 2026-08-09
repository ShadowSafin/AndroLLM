package io.androllm.core.accessibility.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.androllm.core.accessibility.AccessibilityAutomationService
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.gestures.GestureExecutor
import io.androllm.core.accessibility.util.MainThread
import timber.log.Timber

/**
 * Types and edits text in other apps. Primary path is the accessibility
 * `ACTION_SET_TEXT` (fast, reliable, replaces the field content); when that
 * fails it falls back to the clipboard + `ACTION_PASTE` combo, which works on
 * stubborn WebView/Compose fields.
 */
class TextInputController(
    private val context: Context,
    private val serviceRef: () -> AccessibilityAutomationService?,
    private val gestures: GestureExecutor,
    private val debug: AccessibilityDebugStore
) {

    /** Sets [text] into [node]; returns true on success. */
    suspend fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle) }
        if (ok) return true
        debug.record("input", "SET_TEXT failed — clipboard fallback")
        return pasteViaClipboard(node, text)
    }

    suspend fun copy(node: AccessibilityNodeInfo): Boolean =
        MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_COPY) }

    suspend fun paste(node: AccessibilityNodeInfo): Boolean =
        MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }

    /** Selects everything in the field (ACTION_SET_SELECTION 0..MAX). */
    suspend fun selectAll(node: AccessibilityNodeInfo): Boolean =
        MainThread.action {
            val bundle = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle)
        }

    /** Clear a field by replacing its content with an empty string. */
    suspend fun clear(node: AccessibilityNodeInfo): Boolean = setText(node, "")

    private suspend fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("andro_llm", text))
        val pasted = MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
        if (!pasted) {
            debug.record("failure", "paste into field failed")
            Timber.w("TextInputController: paste failed for field")
        }
        return pasted
    }
}
