package io.androllm.engine.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.engine.api.DefaultEngineRepository
import io.androllm.engine.api.EngineRepository
import io.androllm.engine.api.InferenceEngine
import io.androllm.engine.core.LiteRtLmEngine
import javax.inject.Singleton

/**
 * Hilt bindings for the LiteRT-LM inference engine and its repository facade.
 *
 * The chat/generation runtime is Google's LiteRT-LM Kotlin API
 * (com.google.ai.edge.litertlm); embeddings run on the raw LiteRT
 * CompiledModel API. No native llama.cpp code is involved anymore.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindInferenceEngine(engine: LiteRtLmEngine): InferenceEngine

    @Binds
    @Singleton
    abstract fun bindEngineRepository(repository: DefaultEngineRepository): EngineRepository
}
