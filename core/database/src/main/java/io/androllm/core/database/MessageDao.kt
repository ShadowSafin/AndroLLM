package io.androllm.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for messages.
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String): Int
    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int
    @Query("DELETE FROM messages")
    suspend fun deleteAll(): Int
    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeById(id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun observeByConversationId(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    suspend fun getByConversationId(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun countByConversationId(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT content FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageContent(conversationId: String): String?

    @Query("UPDATE messages SET is_bookmarked = :isBookmarked WHERE id = :id")
    suspend fun updateBookmarked(id: String, isBookmarked: Boolean): Int
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchContent(query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE is_bookmarked = 1 ORDER BY timestamp DESC")
    fun observeBookmarked(): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND timestamp > :timestamp")
    suspend fun deleteMessagesAfterTimestamp(conversationId: String, timestamp: Long): Int
}
