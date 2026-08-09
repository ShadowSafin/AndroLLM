# Database Guide

Room database architecture, schema, and migration guide for AndroLLM.

---

## Overview

AndroLLM uses **Room** (SQLite abstraction) for all local persistence. There are two separate Room database instances:

1. **AppDatabase** — Main app data (conversations, messages, models, settings)
2. **MemoryDatabase** — Memory system data (memories, embeddings, summaries) — opened lazily

Both use WAL (Write-Ahead Logging) mode for concurrent read/write performance.

---

## AppDatabase

**File:** [`core/database/src/main/java/io/androllm/core/database/AppDatabase.kt`](../../core/database/src/main/java/io/androllm/core/database/AppDatabase.kt)

```kotlin
@Database(
    entities = [ConversationEntity, MessageEntity, ModelEntity, SettingsEntity],
    version = AppConstants.Database.VERSION,  // 5
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelDao(): ModelDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app.db"
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)  // WAL mode
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

### Entities

#### ConversationEntity

```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String?,
    val messageCount: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)
```

#### MessageEntity

```kotlin
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKeys(
        foreignKey = ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    )],
    indices = [Index(value = ["conversation_id"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val role: String,           // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long,
    @ColumnInfo(name = "is_pending") val isPending: Boolean = false,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "is_bookmarked") val isBookmarked: Boolean = false,
    @ColumnInfo(name = "origin") val origin: String = "TYPED"  // TYPED | VOICE | AUTOMATION
)
```

#### ModelEntity

```kotlin
@Entity(
    tableName = "models",
    indices = [Index(value = ["name"], unique = true)]
)
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    val format: String = "GGUF",
    val parameters: String?,
    val quantization: String?,
    @ColumnInfo(name = "context_length") val contextLength: Int? = null,
    @ColumnInfo(name = "download_url") val downloadUrl: String? = null,
    @ColumnInfo(name = "is_downloaded") val isDownloaded: Boolean = false,
    @ColumnInfo(name = "is_loaded") val isLoaded: Boolean = false,
    @ColumnInfo(name = "download_status") val downloadStatus: String = "NOT_DOWNLOADED",
    val status: String = "NOT_LOADED",
    val sha256: String? = null,
    val architecture: String?,
    val family: String?,
    val license: String?,
    @ColumnInfo(name = "min_ram_gb") val minRamGb: Float? = null,
    @ColumnInfo(name = "recommended_ram_gb") val recommendedRamGb: Float? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "added_date") val addedDate: Long? = null,
    @ColumnInfo(name = "last_used_date") val lastUsedDate: Long? = null
)
```

#### SettingsEntity

```kotlin
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: String = "app",
    val theme: String = "SYSTEM",
    val language: String = "en",
    @ColumnInfo(name = "storage_path") val storagePath: String? = null,
    @ColumnInfo(name = "developer_mode") val developerMode: Boolean = false,
    @ColumnInfo(name = "first_launch") val firstLaunch: Boolean = true,
    @ColumnInfo(name = "model_path") val modelPath: String? = null,
    @ColumnInfo(name = "gemini_api_key_encrypted") val geminiApiKeyEncrypted: String? = null
)
```

---

## Migrations

### Migration 1→2

Added pinning/archiving to conversations and bookmarking to messages:
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN is_bookmarked INTEGER NOT NULL DEFAULT 0")
    }
}
```

### Migration 2→3

Added license column to models:
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE models ADD COLUMN license TEXT")
    }
}
```

### Migration 3→4

Added message origin column:
```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN origin TEXT NOT NULL DEFAULT 'TYPED'")
    }
}
```

### Migration 4→5

Added Gemini API key encrypted column to settings:
```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN gemini_api_key_encrypted TEXT")
    }
}
```

---

## DAOs

### ConversationDao

```kotlin
@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE is_archived = 0 ORDER BY updated_at DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE is_pinned = 1 ORDER BY updated_at DESC")
    fun observePinned(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET is_pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET is_archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Delete
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("UPDATE conversations SET message_count = message_count + 1, updated_at = :now WHERE id = :id")
    suspend fun incrementMessageCount(id: String, now: Long)
}
```

### MessageDao

```kotlin
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun observeByConversationId(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Delete
    suspend fun deleteById(id: String)

    @Query("UPDATE messages SET is_bookmarked = :bookmarked WHERE id = :id")
    suspend fun setBookmarked(id: String, bookmarked: Boolean)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND timestamp > :afterTimestamp")
    suspend fun truncateAfterTimestamp(conversationId: String, afterTimestamp: Long)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    fun search(conversationId: String, query: String): Flow<List<MessageEntity>>
}
```

### ModelDao

```kotlin
@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("UPDATE models SET is_loaded = :loaded WHERE id = :id")
    suspend fun updateLoaded(id: String, loaded: Boolean)

    @Delete
    suspend fun delete(id: String)

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models ORDER BY last_used_date DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE is_downloaded = 1 ORDER BY last_used_date DESC")
    fun observeDownloaded(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultModel(): ModelEntity?

    @Query("SELECT * FROM models WHERE name LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'")
    fun searchModels(query: String): Flow<List<ModelEntity>>
}
```

### SettingsDao

```kotlin
@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 'app' LIMIT 1")
    fun observeSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 'app' LIMIT 1")
    suspend fun getSettings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)
}
```

---

## Repositories

Repositories wrap DAOs and handle domain-level conversions:

| Repository | DAO | Purpose |
|---|---|---|
| `ConversationRepository` | `ConversationDao` | CRUD + title updates + pin/archive |
| `MessageRepository` | `MessageDao` | Message CRUD + search + truncation |
| `ModelRepository` | `ModelDao` | Model CRUD + load/download metadata |
| `SettingsRepository` | `SettingsDao` | Settings CRUD |

---

## MemoryDatabase

**File:** [`core/memory/src/main/java/io/androllm/core/memory/db/MemoryDatabase.kt`](../../core/memory/src/main/java/io/androllm/core/memory/db/MemoryDatabase.kt)

A separate Room instance opened **lazily** — only when memory features are enabled:

```kotlin
@Database(
    entities = [MemoryEntity, EmbeddingEntity, ProjectEntity, TagEntity, RelationshipEntity, SummaryEntity],
    version = 1,
    exportSchema = true
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun projectDao(): ProjectDao
    abstract fun tagDao(): TagDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun summaryDao(): SummaryDao

    companion object {
        @Volatile private var INSTANCE: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "memory.db"
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

The memory database is stored at `{app_data_dir}/databases/memory.db`.

---

## Performance Considerations

### WAL Mode

Both databases use `JournalMode.WRITE_AHEAD_LOGGING`:
- Readers don't block writers
- Writers don't block readers
- Better concurrent performance for chat (many reads during streaming)

### Flow-Based Observation

All DAO queries return `Flow` for reactive UI updates:
```kotlin
// ViewModel observes conversation list reactively
conversationRepository.observeAll().collect { conversations ->
    _uiState.value = UiState.Success(conversations)
}
```

### Indexes

Key indexes for performance:
- `messages.conversation_id` — Fast conversation message loading
- `models.name` (unique) — Fast model lookup
- `conversations.updated_at` — Implicit via ORDER BY (consider adding explicit index for large datasets)

🚧 **Planned:** Explicit indexes on `conversations.updated_at` and `messages.timestamp` for larger datasets.

---

## Backup and Restore

### Export All Data

```kotlin
// Copy database files to external storage
val appDb = File(context.getDatabasePath("app.db").path)
val memoryDb = File(context.getDatabasePath("memory.db").path)
// Copy to backup location...
```

### Clear All Data

Settings → Storage → Clear cache deletes the in-memory vector index but preserves the database. To fully reset:
```kotlin
// Settings → Developer Options → Reset database
Room.databaseBuilder(...)
    .fallbackToDestructiveMigration()  // Only in debug/dev builds
    .build()
```

⚠️ Destructive migration deletes ALL data. Use only in debug builds.

---

## Testing

**Test class:** `EntityMappingTest` in `core/database/src/test/`

Tests verify:
- Entity-to-domain conversion correctness
- DAO query result mapping
- Foreign key cascade behavior
- Migration compatibility (via `MigrationTest` in instrumented tests)

See [TESTING.md](../TESTING.md) for test conventions.
