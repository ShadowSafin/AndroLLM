package io.androllm.core.memory.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.androllm.core.memory.db.entity.EMBEDDING_TABLE
import io.androllm.core.memory.db.entity.EmbeddingEntity
import io.androllm.core.memory.db.entity.MEMORY_ENTITY_TABLE
import io.androllm.core.memory.db.entity.MEMORY_TAG_CROSS_REF_TABLE
import io.androllm.core.memory.db.entity.MemoryEntity
import io.androllm.core.memory.db.entity.MemoryTagCrossRef
import io.androllm.core.memory.db.entity.PROJECT_TABLE
import io.androllm.core.memory.db.entity.ProjectEntity
import io.androllm.core.memory.db.entity.RELATIONSHIP_TABLE
import io.androllm.core.memory.db.entity.RelationshipEntity
import io.androllm.core.memory.db.entity.SUMMARY_TABLE
import io.androllm.core.memory.db.entity.SummaryEntity
import io.androllm.core.memory.db.entity.TAG_TABLE
import io.androllm.core.memory.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memories: List<MemoryEntity>)

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("SELECT * FROM $MEMORY_ENTITY_TABLE WHERE id = :id")
    suspend fun getById(id: String): MemoryEntity?

    @Query("SELECT * FROM $MEMORY_ENTITY_TABLE WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MemoryEntity>

    @Query("SELECT * FROM $MEMORY_ENTITY_TABLE ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM $MEMORY_ENTITY_TABLE ORDER BY updated_at DESC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM $MEMORY_ENTITY_TABLE ORDER BY is_pinned DESC, updated_at DESC")
    fun observeAllWithPinnedFirst(): Flow<List<MemoryEntity>>

    @Query("DELETE FROM $MEMORY_ENTITY_TABLE WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM $MEMORY_ENTITY_TABLE WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM $MEMORY_ENTITY_TABLE")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM $MEMORY_ENTITY_TABLE")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM $MEMORY_ENTITY_TABLE WHERE category = :category")
    suspend fun countByCategory(category: String): Int

    @Query("UPDATE $MEMORY_ENTITY_TABLE SET is_pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("UPDATE $MEMORY_ENTITY_TABLE SET is_archived = :archived WHERE id = :id")
    suspend fun updateArchived(id: String, archived: Boolean)

    @Query(
        "UPDATE $MEMORY_ENTITY_TABLE SET access_count = access_count + 1, last_accessed_at = :now WHERE id = :id"
    )
    suspend fun bumpAccess(id: String, now: Long)

    @Query("UPDATE $MEMORY_ENTITY_TABLE SET importance = :importance, updated_at = :now WHERE id = :id")
    suspend fun updateImportance(id: String, importance: Int, now: Long)

    /**
     * Returns the ids of memories matching the given filters. `tag` filters by
     * a single tag name; call once per tag and union the results for multi-tag
     * queries (match-any semantics).
     */
    @Query(
        """
        SELECT DISTINCT m.id FROM $MEMORY_ENTITY_TABLE m
        WHERE (:category IS NULL OR m.category = :category)
          AND (:projectId IS NULL OR m.project_id = :projectId)
          AND (:pinnedOnly = 0 OR m.is_pinned = 1)
          AND (:includeArchived = 1 OR m.is_archived = 0)
          AND m.importance >= :minImportance
          AND (:tag IS NULL OR EXISTS (
                SELECT 1 FROM $MEMORY_TAG_CROSS_REF_TABLE c
                JOIN $TAG_TABLE t ON t.id = c.tag_id
                WHERE c.memory_id = m.id AND t.name = :tag
              ))
        """
    )
    suspend fun getFilteredIds(
        category: String?,
        projectId: String?,
        pinnedOnly: Boolean,
        includeArchived: Boolean,
        minImportance: Int,
        tag: String?
    ): List<String>

    @Query(
        "SELECT DISTINCT id FROM $MEMORY_ENTITY_TABLE WHERE content LIKE '%' || :query || '%'"
    )
    suspend fun searchContentIds(query: String): List<String>

    /**
     * Memories that have no embedding row yet — the background indexing queue.
     * Embeddings are an optimization: these memories remain fully usable via
     * keyword/recency retrieval while the queue is being drained.
     */
    @Query(
        """
        SELECT id FROM $MEMORY_ENTITY_TABLE
        WHERE id NOT IN (SELECT memory_id FROM $EMBEDDING_TABLE)
        """
    )
    suspend fun getMemoryIdsWithoutEmbeddings(): List<String>

    /**
     * Returns (memoryId, tagName) pairs for the given memories in one query.
     */
    @Query(
        """
        SELECT m.id AS memoryId, t.name AS tagName
        FROM $MEMORY_ENTITY_TABLE m
        JOIN $MEMORY_TAG_CROSS_REF_TABLE c ON c.memory_id = m.id
        JOIN $TAG_TABLE t ON t.id = c.tag_id
        WHERE m.id IN (:ids)
        ORDER BY t.name ASC
        """
    )
    suspend fun getTagsForMemoryIds(ids: List<String>): List<MemoryTagRow>

    data class MemoryTagRow(
        val memoryId: String,
        val tagName: String
    )

    @Query(
        """
        SELECT DISTINCT c.memory_id FROM $MEMORY_TAG_CROSS_REF_TABLE c
        JOIN $TAG_TABLE t ON t.id = c.tag_id
        WHERE t.name LIKE '%' || :query || '%'
        """
    )
    suspend fun searchTagIds(query: String): List<String>
}

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(embeddings: List<EmbeddingEntity>)

    @Query("SELECT * FROM $EMBEDDING_TABLE WHERE memory_id = :memoryId")
    suspend fun getByMemoryId(memoryId: String): EmbeddingEntity?

    @Query("SELECT * FROM $EMBEDDING_TABLE")
    suspend fun getAll(): List<EmbeddingEntity>

    @Query("SELECT * FROM $EMBEDDING_TABLE WHERE memory_id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<EmbeddingEntity>

    @Query("SELECT COUNT(*) FROM $EMBEDDING_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $EMBEDDING_TABLE WHERE memory_id = :memoryId")
    suspend fun deleteByMemoryId(memoryId: String)

    @Query("DELETE FROM $EMBEDDING_TABLE WHERE memory_id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM $EMBEDDING_TABLE WHERE model_path != :modelPath")
    suspend fun deleteByModelPathNot(modelPath: String)

    @Query("DELETE FROM $EMBEDDING_TABLE")
    suspend fun deleteAll()
}

@Dao
interface SummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SummaryEntity)

    @Query("SELECT * FROM $SUMMARY_TABLE WHERE conversation_id = :conversationId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestForConversation(conversationId: String): SummaryEntity?

    @Query("SELECT * FROM $SUMMARY_TABLE ORDER BY updated_at DESC")
    suspend fun getAll(): List<SummaryEntity>

    @Query("SELECT * FROM $SUMMARY_TABLE WHERE conversation_id = :conversationId ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getForConversation(conversationId: String, limit: Int): List<SummaryEntity>

    @Query("SELECT COUNT(*) FROM $SUMMARY_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $SUMMARY_TABLE")
    suspend fun deleteAll()

    @Query("DELETE FROM $SUMMARY_TABLE WHERE conversation_id = :conversationId")
    suspend fun deleteForConversation(conversationId: String)
}

@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("SELECT * FROM $PROJECT_TABLE WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ProjectEntity?

    @Query("SELECT * FROM $PROJECT_TABLE WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Query("SELECT * FROM $PROJECT_TABLE ORDER BY name ASC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM $PROJECT_TABLE ORDER BY name ASC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT COUNT(*) FROM $PROJECT_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $PROJECT_TABLE")
    suspend fun deleteAll()
}

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM $TAG_TABLE WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM $TAG_TABLE WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Query("SELECT * FROM $TAG_TABLE ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT COUNT(*) FROM $TAG_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $TAG_TABLE")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: MemoryTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(crossRefs: List<MemoryTagCrossRef>)

    @Query("DELETE FROM $MEMORY_TAG_CROSS_REF_TABLE WHERE memory_id = :memoryId")
    suspend fun deleteCrossRefsForMemory(memoryId: String)

    @Query("SELECT t.name FROM $MEMORY_TAG_CROSS_REF_TABLE c JOIN $TAG_TABLE t ON t.id = c.tag_id WHERE c.memory_id = :memoryId ORDER BY t.name")
    suspend fun getTagNamesForMemory(memoryId: String): List<String>

    @Query("SELECT memory_id FROM $MEMORY_TAG_CROSS_REF_TABLE WHERE tag_id = :tagId")
    suspend fun getMemoryIdsForTag(tagId: String): List<String>

    @Delete
    suspend fun deleteCrossRef(crossRef: MemoryTagCrossRef)
}

@Dao
interface RelationshipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: RelationshipEntity)

    @Query("SELECT * FROM $RELATIONSHIP_TABLE WHERE from_memory_id = :memoryId OR to_memory_id = :memoryId")
    suspend fun getForMemory(memoryId: String): List<RelationshipEntity>

    @Query("SELECT * FROM $RELATIONSHIP_TABLE")
    suspend fun getAll(): List<RelationshipEntity>

    @Query("SELECT COUNT(*) FROM $RELATIONSHIP_TABLE")
    suspend fun count(): Int

    @Query("DELETE FROM $RELATIONSHIP_TABLE")
    suspend fun deleteAll()
}
