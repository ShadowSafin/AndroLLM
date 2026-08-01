package io.androllm.core.database.repository

import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.database.MessageDao
import io.androllm.core.database.toDomain
import io.androllm.core.database.toEntity
import io.androllm.core.models.Message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for messages backed by Room.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao
) {

    /**
     * Observes all messages of a conversation.
     */
    fun observeByConversationId(conversationId: String): Flow<List<Message>> =
        messageDao.observeByConversationId(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Inserts or updates a message.
     */
    suspend fun upsert(message: Message): Result<Unit> = io.androllm.core.common.runCatching {
        messageDao.upsert(message.toEntity())
    }

    /**
     * Inserts or updates a batch of messages.
     */
    suspend fun upsertAll(messages: List<Message>): Result<Unit> = io.androllm.core.common.runCatching {
        messageDao.upsertAll(messages.map { it.toEntity() })
    }

    /**
     * Deletes a message by ID.
     */
    suspend fun deleteById(id: String): Result<Unit> = io.androllm.core.common.runCatching {
        messageDao.deleteById(id)
    }

    /**
     * Deletes all messages of a conversation.
     */
    suspend fun deleteByConversationId(conversationId: String): Result<Unit> = io.androllm.core.common.runCatching {
        messageDao.deleteByConversationId(conversationId)
    }

    /**
     * Returns the last message content of a conversation (for previews).
     */
    suspend fun getLastMessageContent(conversationId: String): Result<String?> = io.androllm.core.common.runCatching {
        messageDao.getLastMessageContent(conversationId)
    }

    /**
     * Returns the message count of a conversation.
     */
    suspend fun countByConversationId(conversationId: String): Result<Int> = io.androllm.core.common.runCatching {
        messageDao.countByConversationId(conversationId)
    }

    /**
     * Toggles message bookmark status.
     */
    suspend fun setBookmarked(id: String, isBookmarked: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        messageDao.updateBookmarked(id, isBookmarked)
    }

    /**
     * Searches messages containing the given text query.
     */
    fun searchContent(query: String): Flow<List<Message>> =
        messageDao.searchContent(query).map { entities -> entities.map { it.toDomain() } }

    /**
     * Deletes messages after a specific timestamp for a conversation (e.g. for prompt editing/branching).
     */
    suspend fun truncateAfterTimestamp(conversationId: String, timestamp: Long): Result<Unit> = io.androllm.core.common.runCatching {
        messageDao.deleteMessagesAfterTimestamp(conversationId, timestamp)
    }
}
