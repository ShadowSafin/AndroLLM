package io.androllm.core.accessibility.controller

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.accessibility.AccessibilityAutomationService
import io.androllm.core.accessibility.analyzer.ScreenAnalyzer
import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.finder.LiveTree
import io.androllm.core.accessibility.finder.UiSelector
import io.androllm.core.accessibility.gestures.GestureExecutor
import io.androllm.core.accessibility.input.TextInputController
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.util.MainThread
import io.androllm.core.tools.tool.impl.ToolIntents
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/** Outcome of a single UI action (tap / type / scroll / navigate). */
data class UiActionResult(val success: Boolean, val message: String) {
    companion object {
        fun ok(msg: String) = UiActionResult(true, msg)
        fun fail(msg: String) = UiActionResult(false, msg)
    }
}

/**
 * The single facade of the accessibility automation engine. Tools, the
 * executor and the screen analyzer all talk to this class; it owns the
 * service binding, routes every node operation to the main thread and keeps
 * CPU low by caching nothing between actions (trees are cheap to rebuild and
 * never stale).
 */
@Singleton
class AccessibilityController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: AccessibilitySettingsStore,
    private val windowMonitor: WindowMonitor,
    private val uiState: UIStateTracker,
    private val screenAnalyzer: ScreenAnalyzer,
    private val debug: AccessibilityDebugStore
) {

    @Volatile private var service: AccessibilityAutomationService? = null

    private val gestures = GestureExecutor({ service }, debug)
    private val textInput = TextInputController(context, { service }, gestures, debug)

    /** True while the user has enabled the service in system settings. */
    val isConnected: Boolean get() = service != null

    internal fun serviceOrNull(): AccessibilityAutomationService? = service

    internal fun bind(service: AccessibilityAutomationService) {
        this.service = service
        debug.record("service", "connected")
    }

    internal fun unbind() {
        service = null
        debug.record("service", "disconnected")
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        windowMonitor.onEvent(event)
        uiState.onEvent(event)
    }

    /** Runs [block] on the main thread; null when the node access failed. */
    suspend fun <T> onMain(block: () -> T): T? = MainThread.node(block)

    // ── Screen reading ──────────────────────────────────────────────────────

    suspend fun readScreen(): UiScreenSnapshot = screenAnalyzer.read(this)

    // ── Element actions ─────────────────────────────────────────────────────

    /** Taps the element matching [selector], scrolling to it first if needed. */
    suspend fun click(selector: UiSelector, maxScrolls: Int = 6): UiActionResult {
        val settings = settingsStore.current()
        val root = onMain { service?.rootInActiveWindow }
        if (root == null) return UiActionResult.fail(notConnected())
        var node = onMain { LiveTree.find(root, selector) }
        var scrolls = 0
        while (node == null && settings.autoScrollIntoView && scrolls < maxScrolls) {
            val scrolled = onMain {
                LiveTree.findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            } ?: false
            if (!scrolled) break
            scrolls++
            node = onMain { LiveTree.find(root, selector) }
        }
        if (node == null) {
            return UiActionResult.fail("Element '${selector.summary}' was not found on this screen.")
        }
        val visible = onMain { node.isVisibleToUser } ?: false
        if (!visible) return UiActionResult.fail("Element '${selector.summary}' is not visible.")
        val ok = gestures.clickNode(node)
        val msg = if (ok) "Tapped ${selector.summary}." else "Tapping ${selector.summary} failed."
        return if (ok) UiActionResult.ok(msg) else UiActionResult.fail(msg)
    }

    /** Double-taps the element matching [selector] (e.g. Maps zoom). */
    suspend fun doubleTap(selector: UiSelector): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        val node = onMain { LiveTree.find(root, selector) }
            ?: return UiActionResult.fail("Element '${selector.summary}' was not found.")
        val bounds = android.graphics.Rect()
        onMain { node.getBoundsInScreen(bounds) }
        val ok = gestures.doubleTap(bounds)
        return if (ok) UiActionResult.ok("Double-tapped ${selector.summary}.") else UiActionResult.fail("Double-tap failed.")
    }

    /**
     * Drags the element matching [selector] by ([dx], [dy]) screen points
     * (sliders, reorder handles, maps panning).
     */
    suspend fun drag(selector: UiSelector, dx: Float, dy: Float): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        val node = onMain { LiveTree.find(root, selector) }
            ?: return UiActionResult.fail("Element '${selector.summary}' was not found.")
        val bounds = android.graphics.Rect()
        onMain { node.getBoundsInScreen(bounds) }
        val ok = gestures.drag(
            bounds.exactCenterX(), bounds.exactCenterY(),
            bounds.exactCenterX() + dx, bounds.exactCenterY() + dy
        )
        return if (ok) UiActionResult.ok("Dragged ${selector.summary}.") else UiActionResult.fail("Drag failed.")
    }

    /** Two-finger pinch around the screen center. */
    suspend fun pinch(zoomIn: Boolean): UiActionResult {
        val dm = context.resources.displayMetrics
        val ok = gestures.pinch(dm.widthPixels / 2f, dm.heightPixels / 2f, zoomIn)
        return if (ok) {
            UiActionResult.ok(if (zoomIn) "Pinched in." else "Pinched out.")
        } else {
            UiActionResult.fail("Pinch failed.")
        }
    }

    /** Long-presses the element matching [selector]. */
    suspend fun longClick(selector: UiSelector): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        val node = onMain { LiveTree.find(root, selector) }
            ?: return UiActionResult.fail("Element '${selector.summary}' was not found.")
        val bounds = android.graphics.Rect()
        onMain { node.getBoundsInScreen(bounds) }
        val ok = gestures.longPress(bounds)
        return if (ok) UiActionResult.ok("Long-pressed ${selector.summary}.") else UiActionResult.fail("Long-press failed.")
    }

    /** Types [text] into the focused field, or the field matching [into]. */
    suspend fun type(text: String, into: String?): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        val field = onMain {
            if (into != null) {
                LiveTree.find(root, UiSelector(textContains = into, type = UiElementType.TEXT_FIELD))
            } else {
                LiveTree.findFocused(root) ?: LiveTree.findFirstEditable(root)
            }
        }
        if (field == null) {
            val what = into?.let { " a field matching '$it'" } ?: ""
            return UiActionResult.fail("No text field$what is available on this screen.")
        }
        val ok = textInput.setText(field, text)
        return if (ok) {
            UiActionResult.ok("Typed into the field${into?.let { " ($it)" } ?: ""}.")
        } else {
            UiActionResult.fail("Typing failed — the field may not accept text.")
        }
    }

    // ── Scrolling & gestures ────────────────────────────────────────────────

    suspend fun scroll(direction: String): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        val scrollable = onMain { LiveTree.findScrollable(root) }
        val ok = when (direction) {
            "up" -> scrollable?.let { gestures.scrollBackward(it) } ?: swipeGesture("down")
            "down" -> scrollable?.let { gestures.scrollForward(it) } ?: swipeGesture("up")
            else -> swipeGesture(direction)
        }
        return if (ok) UiActionResult.ok("Scrolled $direction.") else UiActionResult.fail("Could not scroll $direction.")
    }

    suspend fun swipe(direction: String): UiActionResult {
        val ok = swipeGesture(direction)
        return if (ok) UiActionResult.ok("Swiped $direction.") else UiActionResult.fail("Swipe $direction failed.")
    }

    private suspend fun swipeGesture(direction: String): Boolean {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (fx, fy, dx, dy) = when (direction) {
            "up" -> arrayOf(w / 2, h * 0.75f, 0f, -h * 0.45f)
            "down" -> arrayOf(w / 2, h * 0.25f, 0f, h * 0.45f)
            "left" -> arrayOf(w * 0.8f, h / 2, -w * 0.45f, 0f)
            "right" -> arrayOf(w * 0.2f, h / 2, w * 0.45f, 0f)
            else -> return false
        }
        return gestures.swipe(fx, fy, dx, dy)
    }

    // ── System navigation ───────────────────────────────────────────────────

    suspend fun navigate(action: String): UiActionResult {
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "split_screen" -> {
                if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                else return UiActionResult.fail("Split screen is not supported on this Android version.")
            }
            else -> return UiActionResult.fail("Unknown navigation action '$action'.")
        }
        val ok = gestures.globalAction(code)
        return if (ok) UiActionResult.ok("Performed $action.") else UiActionResult.fail("Could not perform $action.")
    }

    // ── App launching ───────────────────────────────────────────────────────

    /** Launches an installed app by its visible label ("open WhatsApp"). */
    suspend fun launchApp(label: String): UiActionResult {
        val pkg = resolveAppPackage(label)
            ?: return UiActionResult.fail("No installed app matches '$label'.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return UiActionResult.fail("App '$label' ($pkg) has no launcher activity.")
        val launched = ToolIntents.launch(context, intent)
        return if (launched) {
            UiActionResult.ok("Opened $label.")
        } else {
            UiActionResult.fail("Could not open $label.")
        }
    }

    private fun resolveAppPackage(label: String): String? {
        val query = label.trim()
        if (query.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getInstalledApplications(0).firstOrNull { app ->
                pm.getApplicationLabel(app).toString()
                    .contains(query, ignoreCase = true)
            }?.packageName
        }.getOrNull()
    }

    // ── Vision support (screenshot + OCR) ───────────────────────────────────

    /** Captures the screen as a bitmap (API 30+; needs the service connected). */
    suspend fun captureScreenshot(): Bitmap? {
        val s = service ?: return null
        if (Build.VERSION.SDK_INT < 30) return null
        // takeScreenshot must be invoked from the main thread; the result
        // arrives on the main executor, so both sides stay on main. A timeout
        // guarantees the coroutine can never hang on a rejected capture.
        return withTimeoutOrNull(5_000L) { suspendCancellableCoroutine { cont ->
            MainThread.post {
                runCatching {
                    val cb = object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bmp = screenshot.hardwareBuffer?.let { Bitmap.wrapHardwareBuffer(it, null) }
                            cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            Timber.w("AccessibilityController: screenshot failed code=$errorCode")
                            cont.resume(null)
                        }
                    }
                    s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, context.mainExecutor, cb)
                }.onFailure {
                    Timber.w(it, "AccessibilityController: takeScreenshot rejected")
                    cont.resume(null)
                }
            }
        }
        }
    }

    // ── Status helpers ──────────────────────────────────────────────────────

    fun openSystemSettings(): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        return ToolIntents.launch(context, intent)
    }

    private fun notConnected(): String =
        "The accessibility service is not enabled. Open Settings → Accessibility → AndroLLM UI Automation and turn it on, then try again."
}
