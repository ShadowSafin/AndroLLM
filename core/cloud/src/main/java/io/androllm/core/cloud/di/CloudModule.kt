package io.androllm.core.cloud.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.androllm.core.cloud.CloudSettingsRepository
import io.androllm.core.cloud.CloudSettingsStore
import io.androllm.core.cloud.network.LiteLLMClient
import io.androllm.core.cloud.security.AndroidKeyCipher
import io.androllm.core.cloud.security.KeyCipher
import javax.inject.Singleton

/**
 * Hilt wiring for the LiteLLM gateway.
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudModule {

    @Provides
    @Singleton
    fun provideLiteLLMClient(): LiteLLMClient = LiteLLMClient()

    @Provides
    @Singleton
    fun provideKeyCipher(@ApplicationContext context: Context): KeyCipher = AndroidKeyCipher(context)

    @Provides
    @Singleton
    fun provideCloudSettingsRepository(store: CloudSettingsStore): CloudSettingsRepository = store
}
