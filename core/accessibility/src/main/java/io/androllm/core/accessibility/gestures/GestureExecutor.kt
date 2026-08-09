package io.androllm.core.accessibility.gestures

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import io.androllm.core.accessibility.AccessibilityAutomationService
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.util.MainThread
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Performs touch gestures through the accessibility service. Every call is
 * dispatched asynchronously and awaited; failures (gesture rejected, node
 * gone) are reported instead of crashing.
 */
class GestureExecutor(
    private val serviceRef: () -> AccessibilityAutomationService?,
    private val debug: AccessibilityDebugStore
) {

    private val handler = Handler(Looper.getMainLooper())

    /** Tap the center of [bounds]. */
    suspend fun tap(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        return dispatch(
            path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) },
            durationMs = 60,
            label = "tap@(${bounds.exactCenterX().toInt()},${bounds.exactCenterY().toInt()})"
        )
    }

    suspend fun longPress(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        return dispatch(
            path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) },
            durationMs = 700,
            label = "longPress"
        )
    }

    /** Two quick taps — many apps use double-tap (e.g. Maps zoom). */
    suspend fun doubleTap(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val c1 = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val c2 = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke1 = GestureDescription.StrokeDescription(c1, 0, 70)
        val stroke2 = GestureDescription.StrokeDescription(c2, 130, 70)
        return dispatchMulti(listOf(stroke1, stroke2), "doubleTap")
    }

    /** Swipe along [dx]/[dy] from ([fromX], [fromY]). */
    suspend fun swipe(fromX: Float, fromY: Float, dx: Float, dy: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(fromX + dx, fromY + dy)
        }
        return dispatch(path, durationMs, "swipe(${dx.toInt()},${dy.toInt()})")
    }

    /** Drag [from] → [to] (sliders / reordering). */
    suspend fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 600): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(path, durationMs, "drag")
    }

    /**
     * Two-finger pinch around [cx]/[cy]. [zoomIn] moves both fingers toward
     * the center (zoom in), otherwise away from it (zoom out). Both strokes
     * start simultaneously — the standard maps/photo gesture.
     */
    suspend fun pinch(cx: Float, cy: Float, zoomIn: Boolean): Boolean {
        val span = 0.18f * minOf(cx, cy).coerceAtLeast(100f)
        val inner = 0.15f * span
        // Two simultaneous single-line strokes: fingers move toward the center
        // (zoom in) or away from it (zoom out).
        val stroke1 = if (zoomIn) {
            GestureDescription.StrokeDescription(linePath(cx - span, cy, cx - inner, cy), 0, 350)
        } else {
            GestureDescription.StrokeDescription(linePath(cx - inner, cy, cx - span, cy), 0, 350)
        }
        val stroke2 = if (zoomIn) {
            GestureDescription.StrokeDescription(linePath(cx + span, cy, cx + inner, cy), 0, 350)
        } else {
            GestureDescription.StrokeDescription(linePath(cx + inner, cy, cx + span, cy), 0, 350)
        }
        return dispatchMulti(listOf(stroke1, stroke2), if (zoomIn) "pinchIn" else "pinchOut")
    }

    private fun linePath(x0: Float, y0: Float, x1: Float, y1: Float): Path =
        Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }

    /** Click a node — performAction first, synthetic tap as fallback. */
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val ok = MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        if (ok) return true
        val bounds = Rect()
        MainThread.node({ node.getBoundsInScreen(bounds) }, Unit)
        return tap(bounds)
    }

    suspend fun scrollForward(node: AccessibilityNodeInfo): Boolean =
        MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }

    suspend fun scrollBackward(node: AccessibilityNodeInfo): Boolean =
        MainThread.action { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }

    /** System-level actions: back / home / recents / notifications / quick settings. */
    suspend fun globalAction(action: Int): Boolean {
        val service = serviceRef() ?: return false
        val ok = MainThread.action { service.performGlobalAction(action) }
        debug.record("gesture", "globalAction(${globalActionName(action)}) = $ok")
        return ok
    }

    private suspend fun dispatch(path: Path, durationMs: Long, label: String): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchMulti(listOf(stroke), label)
    }

    private suspend fun dispatchMulti(
        strokes: List<GestureDescription.StrokeDescription>,
        label: String
    ): Boolean {
        val service = serviceRef() ?: return false
        val gesture = GestureDescription.Builder().apply { strokes.forEach { addStroke(it) } }.build()
        return suspendCancellableCoroutine { cont ->
            runCatching {
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            debug.record("gesture", "$label = ok")
                            cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            debug.record("gesture", "$label = cancelled")
                            cont.resume(false)
                        }
                    },
                    handler
                )
                if (!dispatched) {
                    debug.record("gesture", "$label = rejected")
                    cont.resume(false)
                }
            }.onFailure {
                // Service tearing down mid-gesture must degrade to a clean
                // failure, never throw out of the executor loop.
                debug.record("failure", "$label: ${it.message}")
                cont.resume(false)
            }
        }
    }

    private fun globalActionName(action: Int): String = when (action) {
        AccessibilityService.GLOBAL_ACTION_BACK -> "back"
        AccessibilityService.GLOBAL_ACTION_HOME -> "home"
        AccessibilityService.GLOBAL_ACTION_RECENTS -> "recents"
        AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> "notifications"
        AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS -> "quick_settings"
        AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN -> "split_screen"
        else -> "action_$action"
    }
}
