package io.androllm.feature.voice.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.androllm.core.voice.VoiceSettingsStore
import io.androllm.feature.voice.VoiceAssistantController

/**
 * Hosts [VoiceOverlay] in a full-screen system overlay window
 * (`TYPE_APPLICATION_OVERLAY`) so the Gemini Live-style floating assistant
 * appears above whatever the user is doing.
 *
 * Requires the "Display over other apps" permission ([OverlayPermission]);
 * when it is missing [show] no-ops and the assistant keeps running via its
 * notification.
 *
 * A plain `TYPE_APPLICATION_OVERLAY` view has no [LifecycleOwner], which makes
 * Compose throw `ViewTreeLifecycleOwner not found` when the view attaches. We
 * install a small [OverlayWindowOwner] (lifecycle + saved state + view model)
 * on the view tree so [ComposeView] can resolve its recomposer.
 */
class VoiceOverlayWindow(
    private val context: Context,
    private val settingsStore: VoiceSettingsStore
) {

    private var view: ComposeView? = null
    private var owner: OverlayWindowOwner? = null
    private var showPending = false
    private val mainHandler = Handler(Looper.getMainLooper())

    val isShowing: Boolean get() = view != null

    /**
     * Adds the overlay window. WindowManager calls must happen on the main
     * thread, so everything is posted there. Returns true when the overlay
     * was (or is being) shown; false when the overlay permission is missing.
     *
     * Idempotent: repeated calls while a window is already added OR still
     * queued on the main thread are no-ops. (Without the [showPending]
     * guard, rapid state emissions can post multiple addView calls and stack
     * duplicate overlay windows.)
     */
    fun show(controller: VoiceAssistantController): Boolean {
        if (view != null || showPending) return true
        if (!OverlayPermission.isGranted(context)) return false

        showPending = true
        mainHandler.post {
            if (view == null) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val owner = OverlayWindowOwner()
                val composeView = ComposeView(context).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeViewModelStoreOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setContent {
                        io.androllm.core.ui.theme.AndroLLMTheme {
                            VoiceOverlay(controller = controller, settingsStore = settingsStore)
                        }
                    }
                }
                owner.onCreate()

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                    y = 0
                }

                if (runCatching { wm.addView(composeView, params) }.isSuccess) {
                    view = composeView
                    this@VoiceOverlayWindow.owner = owner
                } else {
                    owner.destroy()
                }
            }
            showPending = false
        }
        return true
    }

    fun hide() {
        mainHandler.post {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            view?.let {
                runCatching { wm.removeView(it) }
            }
            view = null
            owner?.destroy()
            owner = null
        }
    }
}

/**
 * Minimal [LifecycleOwner] + [ViewModelStoreOwner] + [SavedStateRegistryOwner]
 * for a floating Compose overlay. Mirrors what a ComponentActivity installs on
 * its window tree, kept tiny because the overlay plays after the mic service.
 */
private class OverlayWindowOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun destroy() {
        savedStateController.performSave(Bundle())
        viewModelStore.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
