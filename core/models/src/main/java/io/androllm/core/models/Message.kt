package io.androllm.core.models

import kotlinx.serialization.Serializable

/**
 * Represents a single message inside a conversation.
 */
@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isPending: Boolean = false,
    val modelId: String? = null,
    val isBookmarked: Boolean = false
)
