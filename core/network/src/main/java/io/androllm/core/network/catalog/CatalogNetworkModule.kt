package io.androllm.core.network.catalog

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.core.models.catalog.CatalogRemoteSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogNetworkModule {

    @Binds
    @Singleton
    abstract fun bindCatalogRemoteSource(
        impl: HfCatalogRemoteSource
    ): CatalogRemoteSource
}
