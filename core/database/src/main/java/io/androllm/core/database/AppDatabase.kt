package io.androllm.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.androllm.core.common.AppConstants

/**
 * Application database. Owns all entities and DAOs.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ModelEntity::class,
        SettingsEntity::class
    ],
    version = AppConstants.Database.VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun modelDao(): ModelDao

    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN is_bookmarked INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN license TEXT NOT NULL DEFAULT 'Apache-2.0'")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // New [MessageOrigin] enum column. Stored as enum name string
                // (TYPED / VOICE / AUTOMATION); the data class defaults to
                // TYPED for legacy rows so no further backfill is needed.
                db.execSQL("ALTER TABLE messages ADD COLUMN origin TEXT NOT NULL DEFAULT 'TYPED'")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Keystore-encrypted Gemini API key used by the voice assistant
                // for Speech-to-Text and Text-to-Speech. Stored as TEXT (the
                // encryption blob is already an opaque string from KeyCipher).
                db.execSQL("ALTER TABLE settings ADD COLUMN gemini_api_key_encrypted TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Backend compatibility flags per installed model (NPU support
                // gating). Defaults keep today's behavior: CPU+GPU supported,
                // NPU off until a catalog entry explicitly opts in.
                db.execSQL("ALTER TABLE models ADD COLUMN supports_cpu INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE models ADD COLUMN supports_gpu INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE models ADD COLUMN supports_npu INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Files attached to a message ("" = none). Stored as a JSON
                // array of ChatAttachment metadata so chat history shows the
                // attachment cards; the file bytes live in the conversation
                // cache, not in the database.
                db.execSQL("ALTER TABLE messages ADD COLUMN attachments_json TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 7 → 8: the previous build shipped v7 with a `rag_sources` column
         * (the removed Knowledge Base feature). v8 renames it to
         * `attachments_json` so chat history keeps rendering attachment cards.
         *
         * Guarded by a PRAGMA check because two upgrade paths reach v8:
         *  - v6 → 8: MIGRATION_6_7 already added `attachments_json`, so there
         *    is nothing to rename here;
         *  - v7 → 8: the old column must be renamed in place (data preserved).
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                val hasRagSources = db.query("PRAGMA table_info(messages)").use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "rag_sources") {
                            found = true
                            break
                        }
                    }
                    found
                }
                if (hasRagSources) {
                    db.execSQL("ALTER TABLE messages RENAME COLUMN rag_sources TO attachments_json")
                }
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN warn_before_opening_ai_links INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Returns the singleton database instance, creating it if necessary.
         */
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    AppConstants.DATABASE_NAME
                )
                    // PERFORMANCE: explicit WAL so reads never block on the
                    // post-response message write and vice versa. Room defaults
                    // to AUTOMATIC (WAL on API 16+) — pin it so writes stay
                    // cheap even when readers are active (chat observer flows).
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
