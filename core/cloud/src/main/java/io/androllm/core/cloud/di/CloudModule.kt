package io.androllm.core.cloud.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.androllm.core.cloud.CloudSettingsRepository
import io.androllm.core.cloud.CloudSettingsStore
import io.androllm.core.cloud.cache.PromptCache
import io.androllm.core.cloud.network.LiteLLMClient
import io.androllm.core.cloud.pipeline.CloudRequestPlanner
import io.androllm.core.cloud.pipeline.CloudResultObserver
import io.androllm.core.cloud.security.AndroidKeyCipher
import io.androllm.core.cloud.security.KeyCipher
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.cloud.usage.CloudUsageStore
import io.androllm.core.cloud.usage.FileCloudUsageStore
import java.io.File
import javax.inject.Singleton

/**
 * Hilt wiring for the LiteLLM gateway and the cloud pipeline
 * (usage metering, prompt caching, request planning, result observation).
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

    // ── Usage metering ────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideCloudUsageStore(@ApplicationContext context: Context): CloudUsageStore =
        FileCloudUsageStore(File(context.filesDir, "cloud/cloud-usage.json"))

    @Provides
    @Singleton
    fun provideCloudUsageMeter(store: CloudUsageStore): CloudUsageMeter = CloudUsageMeter(store)

    // ── Prompt caching ────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun providePromptCache(@ApplicationContext context: Context): PromptCache =
        PromptCache(diskFile = File(context.filesDir, "cloud/prompt-cache.json"))

    // ── Pipeline ──────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideCloudRequestPlanner(cache: PromptCache): CloudRequestPlanner = CloudRequestPlanner(cache)

    @Provides
    @Singleton
    fun provideCloudResultObserver(): CloudResultObserver = CloudResultObserver()
}
