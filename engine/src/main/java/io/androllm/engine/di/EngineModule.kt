package io.androllm.engine.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.llama.LlamaCppEngine
import javax.inject.Singleton

/**
 * Hilt bindings for the inference engine and its repository facade.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindInferenceEngine(engine: LlamaCppEngine): InferenceEngine

    @Binds
    @Singleton
    abstract fun bindEngineRepository(repository: DefaultEngineRepository): EngineRepository
}
