package io.androllm.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.androllm.core.common.AppConstants
import io.androllm.core.models.AppSettings
import io.androllm.core.models.ThemeMode

/**
 * Room entity representing application settings (single row).
 */
@Entity(tableName = AppConstants.Database.SETTINGS_TABLE)
data class SettingsEntity(
    @PrimaryKey
    val id: String = "app",
    val theme: String = ThemeMode.SYSTEM.name,
    val language: String = "en",
    @ColumnInfo(name = "storage_path")
    val storagePath: String = "",
    @ColumnInfo(name = "developer_mode")
    val developerMode: Boolean = false,
    @ColumnInfo(name = "first_launch")
    val firstLaunch: Boolean = true,
    @ColumnInfo(name = "model_path")
    val modelPath: String? = null
)

/**
 * Maps a database entity to the domain model.
 */
fun SettingsEntity.toDomain(): AppSettings = AppSettings(
    theme = runCatching { ThemeMode.valueOf(theme) }.getOrDefault(ThemeMode.SYSTEM),
    language = language,
    storagePath = storagePath,
    developerMode = developerMode,
    firstLaunch = firstLaunch,
    modelPath = modelPath
)

/**
 * Maps a domain model to the database entity.
 */
fun AppSettings.toEntity(): SettingsEntity = SettingsEntity(
    theme = theme.name,
    language = language,
    storagePath = storagePath,
    developerMode = developerMode,
    firstLaunch = firstLaunch,
    modelPath = modelPath
)
