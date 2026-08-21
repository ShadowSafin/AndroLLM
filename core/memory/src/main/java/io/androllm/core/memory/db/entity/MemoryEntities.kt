package io.androllm.core.memory.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val MEMORY_ENTITY_TABLE = "memory_entity"
const val EMBEDDING_TABLE = "memory_embeddings"
const val SUMMARY_TABLE = "memory_summaries"
const val PROJECT_TABLE = "memory_projects"
const val TAG_TABLE = "memory_tags"
const val MEMORY_TAG_CROSS_REF_TABLE = "memory_tag_cross_ref"
const val RELATIONSHIP_TABLE = "memory_relationships"

/**
 * A single long-term memory row.
 * Storage spec: id, userId, chatId, type, content, summary, priority,
 * createdAt, updatedAt, lastUsedAt, expiryAt — plus category/tags.
 */
@Entity(
    tableName = MEMORY_ENTITY_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["category"]),
        Index(value = ["type"]),
        Index(value = ["project_id"]),
        Index(value = ["created_at"]),
        Index(value = ["updated_at"]),
        Index(value = ["expiry_at"]),
        Index(value = ["user_id"])
    ]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val category: String,
    val content: String,
    val importance: Int = 1,
    @ColumnInfo(name = "project_id") val projectId: String? = null,
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: String? = null,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "access_count") val accessCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long? = null,
    // Hardened storage spec — new fields at end for backward compat (positional calls in tests)
    @ColumnInfo(name = "user_id") val userId: String = "default",
    @ColumnInfo(name = "chat_id") val chatId: String? = null,
    @ColumnInfo(name = "type") val type: String = "LONG_TERM",
    val summary: String? = null,
    @ColumnInfo(name = "priority") val priority: Int = 1,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "expiry_at") val expiryAt: Long? = null
)

/**
 * The embedding for a memory. Stored as a raw float32 BLOB; dimension is
 * recorded so rows from a different embedding model can be detected and
 * re-embedded safely.
 */
@Entity(
    tableName = EMBEDDING_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["dimension"])]
)
data class EmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "memory_id") val memoryId: String,
    val vector: ByteArray,
    val dimension: Int,
    @ColumnInfo(name = "model_path") val modelPath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return memoryId == other.memoryId && dimension == other.dimension &&
            modelPath == other.modelPath && createdAt == other.createdAt &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = memoryId.hashCode()
}

/**
 * A rolling conversation summary.
 */
@Entity(
    tableName = SUMMARY_TABLE,
    indices = [Index(value = ["conversation_id"]), Index(value = ["updated_at"])]
)
data class SummaryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val summary: String,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * A project grouping memories by name (unique).
 */
@Entity(
    tableName = PROJECT_TABLE,
    indices = [Index(value = ["name"], unique = true)]
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * A normalized tag (unique by name).
 */
@Entity(
    tableName = TAG_TABLE,
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)

/**
 * Many-to-many join between memories and tags.
 */
@Entity(
    tableName = MEMORY_TAG_CROSS_REF_TABLE,
    primaryKeys = ["memory_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tag_id"])]
)
data class MemoryTagCrossRef(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "tag_id") val tagId: String
)

/**
 * A typed link between two memories.
 */
@Entity(
    tableName = RELATIONSHIP_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["from_memory_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["to_memory_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["from_memory_id"]), Index(value = ["to_memory_id"])]
)
data class RelationshipEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_memory_id") val fromMemoryId: String,
    @ColumnInfo(name = "to_memory_id") val toMemoryId: String,
    val type: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
