package io.androllm.core.cloud.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.cloud.runtime.CloudRuntime
import io.androllm.core.runtime.Runtime

/**
 * Registers the cloud provider runtime into the central
 * [io.androllm.core.runtime.RuntimeRegistry] via Hilt multibinding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudRuntimeModule {

    @Binds
    @IntoSet
    abstract fun bindCloudRuntime(runtime: CloudRuntime): Runtime
}
