package io.androllm.core.accessibility.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.accessibility.runtime.AutomationRuntime
import io.androllm.core.runtime.Runtime

/**
 * Registers the UI automation runtime into the central
 * [io.androllm.core.runtime.RuntimeRegistry] via Hilt multibinding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccessibilityRuntimeModule {

    @Binds
    @IntoSet
    abstract fun bindAutomationRuntime(runtime: AutomationRuntime): Runtime
}
