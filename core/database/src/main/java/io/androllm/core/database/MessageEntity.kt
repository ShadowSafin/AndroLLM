package io.androllm.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.androllm.core.common.AppConstants
import io.androllm.core.models.Message
import io.androllm.core.models.MessageOrigin
import io.androllm.core.models.MessageRole

/**
 * Room entity representing a single chat message.
 */
@Entity(
    tableName = AppConstants.Database.MESSAGE_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversation_id"])]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    @ColumnInfo(name = "is_pending")
    val isPending: Boolean = false,
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "is_bookmarked")
    val isBookmarked: Boolean = false,
    /** Persisted as enum name; defaults to [MessageOrigin.TYPED] for legacy rows. */
    @ColumnInfo(name = "origin")
    val origin: String = MessageOrigin.TYPED.name,
    /**
     * Files attached to this message, serialized as a JSON array of
     * [io.androllm.core.attachments.model.ChatAttachment] ("" = none).
     * Stored as TEXT so the chat layer owns the attachment-domain type.
     */
    @ColumnInfo(name = "attachments_json")
    val attachmentsJson: String = ""
)

/**
 * Maps a database entity to the domain model.
 */
fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
    content = content,
    timestamp = timestamp,
    isPending = isPending,
    modelId = modelId,
    isBookmarked = isBookmarked,
    origin = runCatching { MessageOrigin.valueOf(origin) }.getOrDefault(MessageOrigin.TYPED),
    attachmentsJson = attachmentsJson
)

/**
 * Maps a domain model to the database entity.
 */
fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    timestamp = timestamp,
    isPending = isPending,
    modelId = modelId,
    isBookmarked = isBookmarked,
    origin = origin.name,
    attachmentsJson = attachmentsJson
)
