package io.androllm.core.models

import kotlinx.serialization.Serializable

/**
 * Represents a chat conversation between the user and the assistant.
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String? = null,
    val messageCount: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)
