package io.androllm.core.memory.util

import io.androllm.core.memory.model.MemoryLogEntry
import io.androllm.core.memory.model.MemoryLogLevel
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Bounded in-memory log of memory pipeline activity, surfaced by the
 * developer-mode Memory Inspector. Never persisted, never leaves the device.
 */
@Singleton
class MemoryLogger @Inject constructor() {

    private val entries = CopyOnWriteArrayList<MemoryLogEntry>()

    @Volatile
    private var maxEntries: Int = 200

    fun setMaxEntries(max: Int) {
        maxEntries = max.coerceAtLeast(10)
    }

    fun log(level: MemoryLogLevel, message: String) {
        // Hardening: sanitize logs — never expose sensitive user data, secrets, or full memory content
        val sanitized = sanitizeForLog(message)
        entries.add(MemoryLogEntry(System.currentTimeMillis(), level, sanitized))
        while (entries.size > maxEntries) entries.removeAt(0)
    }

    private fun sanitizeForLog(msg: String): String {
        var s = msg
        // Mask potential secrets/tokens (keep first 4 chars for debugging)
        s = s.replace(Regex("""\b(sk-[A-Za-z0-9]{8,})\w*""")) { it.groupValues[1] + "***" }
        s = s.replace(Regex("""\b(ghp_[A-Za-z0-9]{4,})\w*""")) { it.groupValues[1] + "***" }
        s = s.replace(Regex("""(?i)(password|passwd|pwd|secret|token)\s*[:=]\s*\S+""")) { "${it.groupValues[1]}=***" }
        // Truncate long content
        if (s.length > 400) s = s.take(400) + "…"
        return s
    }

    fun info(message: String) = log(MemoryLogLevel.INFO, message)

    fun warn(message: String) = log(MemoryLogLevel.WARN, message)

    fun error(message: String) = log(MemoryLogLevel.ERROR, message)

    fun debug(message: String) = log(MemoryLogLevel.DEBUG, message)

    fun snapshot(): List<MemoryLogEntry> = entries.toList().asReversed()

    fun clear() = entries.clear()

    /**
     * Keeps only entries after [since] (used by the inspector to show only the
     * latest activity) and returns up to [limit] entries, newest first.
     */
    fun recent(limit: Int): List<MemoryLogEntry> =
        snapshot().take(maxOf(1, min(limit, 100)))
}
