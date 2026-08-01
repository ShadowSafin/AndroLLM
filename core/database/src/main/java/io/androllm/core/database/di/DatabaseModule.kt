package io.androllm.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.androllm.core.common.AppConstants
import io.androllm.core.database.AppDatabase
import io.androllm.core.database.ConversationDao
import io.androllm.core.database.MessageDao
import io.androllm.core.database.ModelDao
import io.androllm.core.database.SettingsDao
import javax.inject.Singleton

/**
 * Hilt module providing Room database and DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideConversationDao(database: AppDatabase): ConversationDao = database.conversationDao()

    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideModelDao(database: AppDatabase): ModelDao = database.modelDao()

    @Provides
    @Singleton
    fun provideSettingsDao(database: AppDatabase): SettingsDao = database.settingsDao()
}
