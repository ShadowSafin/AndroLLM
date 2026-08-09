package io.androllm.core.accessibility.analyzer

import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.UIStateTracker
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.finder.AccessibilityNodeFinder
import io.androllm.core.accessibility.ocr.OcrEngine
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.tree.UiTreeBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces [UiScreenSnapshot]s: reads the live window, builds the semantic
 * tree, detects loading state and focus, and merges OCR text when a vision
 * engine is available and enabled. This is the single source of truth the
 * planners and the LLM see.
 */
@Singleton
class ScreenAnalyzer @Inject constructor(
    private val settingsStore: AccessibilitySettingsStore,
    private val uiState: UIStateTracker,
    private val ocrEngine: OcrEngine,
    private val debug: AccessibilityDebugStore
) {

    suspend fun read(controller: AccessibilityController): UiScreenSnapshot {
        val root = controller.onMain { controller.serviceOrNull()?.rootInActiveWindow }
        val uiRoot = root?.let { controller.onMain { UiTreeBuilder.build(it) } }

        val context = uiState.snapshot()
        val loading = uiRoot?.flatten()?.any { it.type == UiElementType.PROGRESS } ?: false
        uiState.setLoading(loading)

        val focused = uiRoot?.let { AccessibilityNodeFinder.findFocused(it) }?.label
            ?: context.focusedText
        val selected = uiRoot?.flatten()?.firstOrNull { it.selected }?.label
            ?: context.selectedText

        var ocrLines = emptyList<String>()
        val settings = settingsStore.current()
        if (settings.ocrEnabled && ocrEngine.isAvailable) {
            controller.captureScreenshot()?.let { bitmap ->
                ocrLines = runCatching { ocrEngine.recognize(bitmap) }.getOrDefault(emptyList())
            }
        }

        val snapshot = UiScreenSnapshot(
            packageName = context.currentPackage,
            windowTitle = context.windowTitle,
            root = uiRoot,
            focusedText = focused,
            selectedText = selected,
            loading = loading,
            ocrLines = ocrLines
        )
        // Developer mode: keep the last full screen dump around.
        if (settings.developerMode) {
            debug.recordScreenDump(snapshot.describe(120))
        }
        return snapshot
    }
}
