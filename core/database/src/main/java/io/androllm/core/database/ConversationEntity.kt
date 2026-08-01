package io.androllm.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.androllm.core.common.AppConstants
import io.androllm.core.models.Conversation

/**
 * Room entity representing a chat conversation.
 */
@Entity(
    tableName = AppConstants.Database.CONVERSATION_TABLE,
    indices = [Index(value = ["updated_at"])]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String? = null,
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false
)

/**
 * Maps a database entity to the domain model.
 */
fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
    isPinned = isPinned,
    isArchived = isArchived
)

/**
 * Maps a domain model to the database entity.
 */
fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessagePreview = lastMessagePreview,
    messageCount = messageCount,
    isPinned = isPinned,
    isArchived = isArchived
)
