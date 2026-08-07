package io.androllm.core.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.androllm.core.memory.db.dao.EmbeddingDao
import io.androllm.core.memory.db.dao.MemoryDao
import io.androllm.core.memory.db.dao.ProjectDao
import io.androllm.core.memory.db.dao.RelationshipDao
import io.androllm.core.memory.db.dao.SummaryDao
import io.androllm.core.memory.db.dao.TagDao
import io.androllm.core.memory.db.entity.EmbeddingEntity
import io.androllm.core.memory.db.entity.MemoryEntity
import io.androllm.core.memory.db.entity.MemoryTagCrossRef
import io.androllm.core.memory.db.entity.ProjectEntity
import io.androllm.core.memory.db.entity.RelationshipEntity
import io.androllm.core.memory.db.entity.SummaryEntity
import io.androllm.core.memory.db.entity.TagEntity

/**
 * Dedicated database for the on-device memory system.
 *
 * Kept separate from the app database (androllm.db) so memory can be enabled,
 * disabled or wiped independently, its schema evolves on its own version line,
 * and it can be opened lazily (only when the memory system is used).
 */
@Database(
    entities = [
        MemoryEntity::class,
        EmbeddingEntity::class,
        SummaryEntity::class,
        ProjectEntity::class,
        TagEntity::class,
        MemoryTagCrossRef::class,
        RelationshipEntity::class
    ],
    version = MemoryDatabase.VERSION,
    exportSchema = true
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun summaryDao(): SummaryDao
    abstract fun projectDao(): ProjectDao
    abstract fun tagDao(): TagDao
    abstract fun relationshipDao(): RelationshipDao

    companion object {
        const val NAME = "memory.db"
        const val VERSION = 2

        /**
         * v1 -> v2: memories gained an archived flag.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE memory_entity ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: MemoryDatabase? = null

        /**
         * Returns the singleton database instance, creating it if necessary.
         */
        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    NAME
                )
                    // PERFORMANCE: explicit WAL so the post-response memory
                    // writes (insert + embeddings + summaries) never block the
                    // chat UI's readers.
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
