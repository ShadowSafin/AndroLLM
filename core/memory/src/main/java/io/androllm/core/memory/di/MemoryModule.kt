package io.androllm.core.memory.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.androllm.core.memory.MemoryManager
import io.androllm.core.memory.MemoryRepository
import io.androllm.core.memory.db.MemoryDatabase
import io.androllm.core.memory.db.dao.EmbeddingDao
import io.androllm.core.memory.db.dao.MemoryDao
import io.androllm.core.memory.db.dao.ProjectDao
import io.androllm.core.memory.db.dao.RelationshipDao
import io.androllm.core.memory.db.dao.SummaryDao
import io.androllm.core.memory.db.dao.TagDao
import io.androllm.core.memory.embedding.EmbeddingProvider
import io.androllm.core.memory.embedding.RoutingEmbeddingProvider
import io.androllm.core.memory.intelligence.MemoryIntelligence
import io.androllm.core.memory.intelligence.RoutingMemoryIntelligence
import javax.inject.Singleton

/**
 * Hilt wiring for the memory system. The memory database is opened lazily
 * (on first DAO access) so enabling the feature costs nothing until it is
 * actually used.
 */
@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {

    @Provides
    @Singleton
    fun provideMemoryDatabase(@ApplicationContext context: Context): MemoryDatabase =
        MemoryDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideMemoryDao(database: MemoryDatabase): MemoryDao = database.memoryDao()

    @Provides
    @Singleton
    fun provideEmbeddingDao(database: MemoryDatabase): EmbeddingDao = database.embeddingDao()

    @Provides
    @Singleton
    fun provideSummaryDao(database: MemoryDatabase): SummaryDao = database.summaryDao()

    @Provides
    @Singleton
    fun provideProjectDao(database: MemoryDatabase): ProjectDao = database.projectDao()

    @Provides
    @Singleton
    fun provideTagDao(database: MemoryDatabase): TagDao = database.tagDao()

    @Provides
    @Singleton
    fun provideRelationshipDao(database: MemoryDatabase): RelationshipDao = database.relationshipDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryBindings {

    /**
     * Memory's public face: the whole app depends on [MemoryManager] only.
     * Provider/model/embedding choices live behind it.
     */
    @Binds
    @Singleton
    abstract fun bindMemoryManager(impl: MemoryRepository): MemoryManager

    /** Extraction/summarization routed to the ACTIVE provider (cloud or local). */
    @Binds
    @Singleton
    abstract fun bindMemoryIntelligence(impl: RoutingMemoryIntelligence): MemoryIntelligence

    /** Embedding indexing routed between the active provider and local GGUF. */
    @Binds
    @Singleton
    abstract fun bindEmbeddingProvider(impl: RoutingEmbeddingProvider): EmbeddingProvider
}
