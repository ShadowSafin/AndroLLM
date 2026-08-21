package io.androllm.core.memory

/**
 * Lifecycle type for memories — determines storage duration, retrieval scope,
 * and expiry handling. Separate from [MemoryCategory] which classifies content.
 *
 * Maps to required "type" field in storage spec.
 */
enum class MemoryType(val label: String, val defaultTtlMs: Long?) {
    /** Current conversation context — not persisted long-term, lives in RAM */
    SHORT_TERM("Short-term", null),

    /** Stable user preferences and important facts — persisted indefinitely */
    LONG_TERM("Long-term", null),

    /** Recent chats and recent prompts — 7-day sliding window */
    SESSION("Session", 7 * 24 * 60 * 60 * 1000L),

    /** Context tied to a specific project or chat thread — scoped by projectId/chatId */
    PROJECT("Project", 30 * 24 * 60 * 60 * 1000L);

    companion object {
        fun fromName(raw: String?): MemoryType {
            if (raw.isNullOrBlank()) return LONG_TERM
            val normalized = raw.trim().uppercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.name == normalized } ?: LONG_TERM
        }
    }
}
