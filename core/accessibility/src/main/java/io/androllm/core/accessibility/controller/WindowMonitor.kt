package io.androllm.core.accessibility.controller

import android.view.accessibility.AccessibilityEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the accessibility event stream for changes that invalidate cached
 * UI state: window switches, content changes, scrolls. The controller uses
 * this to drop its tree cache so the next screen read is fresh, and the
 * executor uses it to know when to re-read instead of acting on stale data.
 */
@Singleton
class WindowMonitor @Inject constructor() {

    /** Monotonic counter bumped on every relevant event — cheap freshness key. */
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    /** Most recent event type — used by the debug panel. */
    private val _lastEventType = MutableStateFlow("")
    val lastEventType: StateFlow<String> = _lastEventType.asStateFlow()

    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                _generation.value++
            }
            else -> Unit
        }
        _lastEventType.value = android.view.accessibility.AccessibilityEvent.eventTypeToString(event.eventType)
    }
}
