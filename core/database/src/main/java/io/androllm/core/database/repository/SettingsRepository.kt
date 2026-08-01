package io.androllm.core.database.repository

import io.androllm.core.common.Result
import io.androllm.core.common.runCatching
import io.androllm.core.database.SettingsDao
import io.androllm.core.database.toDomain
import io.androllm.core.database.toEntity
import io.androllm.core.models.AppSettings
import io.androllm.core.models.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for application settings backed by Room.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao
) {

    /**
     * Observes application settings, defaulting to [AppSettings] defaults.
     */
    fun observeSettings(): Flow<AppSettings> =
        settingsDao.observeSettings().map { entity ->
            entity?.toDomain() ?: AppSettings()
        }

    /**
     * Returns the current settings, inserting defaults if none exist.
     */
    suspend fun getSettings(): Result<AppSettings> = io.androllm.core.common.runCatching {
        settingsDao.getSettings()?.toDomain() ?: AppSettings().also { upsert(it) }
    }

    /**
     * Inserts or replaces the settings row.
     */
    suspend fun upsert(settings: AppSettings): Result<Unit> = io.androllm.core.common.runCatching {
        settingsDao.upsert(settings.toEntity())
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): Result<Unit> = io.androllm.core.common.runCatching {
        val current = settingsDao.getSettings()?.toDomain() ?: AppSettings()
        val updated = transform(current)
        settingsDao.upsert(updated.toEntity())
    }

    suspend fun updateTheme(theme: ThemeMode): Result<Unit> = io.androllm.core.common.runCatching {
        settingsDao.updateTheme(theme.name)
    }

    suspend fun updateLanguage(language: String): Result<Unit> = io.androllm.core.common.runCatching {
        settingsDao.updateLanguage(language)
    }

    suspend fun updateStoragePath(storagePath: String): Result<Unit> = io.androllm.core.common.runCatching {
        settingsDao.updateStoragePath(storagePath)
    }

    suspend fun updateDeveloperMode(enabled: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        settingsDao.updateDeveloperMode(enabled)
    }

    suspend fun updateFirstLaunch(firstLaunch: Boolean): Result<Unit> = io.androllm.core.common.runCatching {
        settingsDao.updateFirstLaunch(firstLaunch)
    }

    suspend fun updateModelPath(modelPath: String?): Result<Unit> = io.androllm.core.common.runCatching {
        settingsDao.updateModelPath(modelPath)
    }
}
