package io.androllm.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for application settings.
 */
@Dao
interface SettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: SettingsEntity)

    @Query("SELECT * FROM settings WHERE id = 'app'")
    fun observeSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 'app'")
    suspend fun getSettings(): SettingsEntity?

    @Query("UPDATE settings SET theme = :theme WHERE id = 'app'")
    suspend fun updateTheme(theme: String)

    @Query("UPDATE settings SET language = :language WHERE id = 'app'")
    suspend fun updateLanguage(language: String)

    @Query("UPDATE settings SET storage_path = :storagePath WHERE id = 'app'")
    suspend fun updateStoragePath(storagePath: String)

    @Query("UPDATE settings SET developer_mode = :enabled WHERE id = 'app'")
    suspend fun updateDeveloperMode(enabled: Boolean)

    @Query("UPDATE settings SET first_launch = :firstLaunch WHERE id = 'app'")
    suspend fun updateFirstLaunch(firstLaunch: Boolean)

    @Query("UPDATE settings SET model_path = :modelPath WHERE id = 'app'")
    suspend fun updateModelPath(modelPath: String?)
}
