package io.androllm.core.accessibility.debug

import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One entry in the developer-mode log. */
data class DebugEntry(
    val category: String,
    val message: String,
    val at: Long = System.currentTimeMillis()
) {
    val timestamp: String get() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(at))
    override fun toString(): String = "[$timestamp] $category: $message"
}

/**
 * Bounded, in-memory log for the developer mode: execution steps, gesture
 * log, node dumps, failures and timing. Everything is capped so a long
 * automation session can never grow memory unboundedly.
 */
@Singleton
class AccessibilityDebugStore @Inject constructor() {

    private val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<DebugEntry>>(emptyList())
    val entries: StateFlow<List<DebugEntry>> = _entries.asStateFlow()

    /** Latest screen dump (single slot, replaced on every read). */
    private val _lastScreenDump = MutableStateFlow<String?>(null)
    val lastScreenDump: StateFlow<String?> = _lastScreenDump.asStateFlow()

    private val history = ConcurrentLinkedDeque<DebugEntry>()

    fun record(category: String, message: String) {
        val entry = DebugEntry(category, message)
        history.addLast(entry)
        while (history.size > MAX_ENTRIES) history.pollFirst()
        _entries.value = history.toList()
    }

    fun recordScreenDump(dump: String) {
        _lastScreenDump.value = dump.take(6_000)
    }

    fun clear() {
        history.clear()
        _entries.value = emptyList()
        _lastScreenDump.value = null
    }
}
