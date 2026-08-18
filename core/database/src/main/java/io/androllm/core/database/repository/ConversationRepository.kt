package io.androllm.core.database.repository

import io.androllm.core.common.BaseRepository
import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.database.ConversationDao
import io.androllm.core.database.MessageDao
import io.androllm.core.database.toDomain
import io.androllm.core.database.toEntity
import io.androllm.core.models.Conversation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for conversations backed by Room.
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : BaseRepository<Conversation, String> {

    override fun getById(id: String): Flow<Result<Conversation>> =
        conversationDao.observeByIdWithStats(id).map { stats ->
            stats?.toDomain()?.let { Result.success(it) } ?: Result.error("Conversation not found: $id")
        }

    override fun getAll(): Flow<Result<List<Conversation>>> =
        conversationDao.observeAllWithStats().map { statsList ->
            Result.success(statsList.map { it.toDomain() })
        }

    /**
     * Returns active (unarchived) conversations with live message counts,
     * last-message previews, and timestamps computed from the messages table.
     */
    fun observeActive(): Flow<List<Conversation>> =
        conversationDao.observeActiveWithStats().map { statsList -> statsList.map { it.toDomain() } }

    /**
     * Returns pinned conversations with live message stats.
     */
    fun observePinned(): Flow<List<Conversation>> =
        conversationDao.observePinnedWithStats().map { statsList -> statsList.map { it.toDomain() } }

    /**
     * Returns archived conversations with live message stats.
     */
    fun observeArchived(): Flow<List<Conversation>> =
        conversationDao.observeArchivedWithStats().map { statsList -> statsList.map { it.toDomain() } }

    /**
     * Returns recent (active, unarchived) conversations with live message stats.
     */
    fun observeRecent(): Flow<List<Conversation>> = observeActive()

    suspend fun setPinned(id: String, isPinned: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        conversationDao.updatePinned(id, isPinned)
    }

    suspend fun setArchived(id: String, isArchived: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        conversationDao.updateArchived(id, isArchived)
    }

    suspend fun updateTitle(id: String, title: String): Result<Unit> = io.androllm.core.common.runCatching {
        conversationDao.updateTitle(id, title, System.currentTimeMillis())
    }

    suspend fun duplicateConversation(id: String): Result<Conversation> = io.androllm.core.common.runCatching {
        val original = conversationDao.getById(id) ?: throw IllegalArgumentException("Conversation $id not found")
        val messages = messageDao.getByConversationId(id)
        val now = System.currentTimeMillis()
        val newId = java.util.UUID.randomUUID().toString()

        val copyConv = original.copy(
            id = newId,
            title = "${original.title} (Copy)",
            createdAt = now,
            updatedAt = now
        )
        conversationDao.upsert(copyConv)

        val copyMessages = messages.map { msg ->
            msg.copy(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = newId
            )
        }
        messageDao.upsertAll(copyMessages)

        copyConv.toDomain()
    }

    fun searchByTitle(query: String): Flow<List<Conversation>> =
        conversationDao.searchByTitleWithStats(query).map { statsList -> statsList.map { it.toDomain() } }

    override suspend fun upsert(entity: Conversation): Result<String> = io.androllm.core.common.runCatching {
        conversationDao.upsert(entity.toEntity())
        entity.id
    }

    override suspend fun deleteById(id: String): Result<Unit> = io.androllm.core.common.runCatching {
        conversationDao.deleteById(id)
        messageDao.deleteByConversationId(id)
    }

    override suspend fun deleteAll(): Result<Unit> = io.androllm.core.common.runCatching {
        conversationDao.deleteAll()
        messageDao.deleteAll()
    }

    override suspend fun existsById(id: String): Result<Boolean> = io.androllm.core.common.runCatching {
        conversationDao.existsById(id)
    }

    override suspend fun count(): Result<Int> = io.androllm.core.common.runCatching {
        conversationDao.count()
    }
}
