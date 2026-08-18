package io.androllm.core.database

import io.androllm.core.models.Conversation

/**
 * Room POJO populated by a JOIN query that computes message statistics
 * live from the messages table instead of relying on stale cached values
 * stored in the conversations table.
 *
 * This avoids the N+1 query problem: a single aggregation query returns
 * all conversations with their real-time message counts, last message
 * previews, and most-recent timestamps.
 */
data class ConversationWithStats(
    // Conversation columns
    val id: String,
    val title: String,
    val createdAt: Long,
    val isPinned: Boolean,
    val isArchived: Boolean,
    // Computed columns from messages table
    val messageCount: Int,
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Long?
) {
    /**
     * Maps to the domain [Conversation] model, using the live-computed
     * statistics instead of the stale stored values.
     */
    fun toDomain(): Conversation = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = lastMessageTimestamp ?: createdAt,
        lastMessagePreview = lastMessagePreview,
        messageCount = messageCount,
        isPinned = isPinned,
        isArchived = isArchived
    )
}
