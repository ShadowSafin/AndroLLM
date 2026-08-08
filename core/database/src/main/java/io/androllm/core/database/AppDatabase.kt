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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
