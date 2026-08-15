package io.androllm.core.mcp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.mcp.runtime.McpRuntime
import io.androllm.core.runtime.Runtime

/**
 * Hilt module for the MCP system — registers the MCP runtime into the
 * central [io.androllm.core.runtime.RuntimeRegistry] via multibinding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class McpModule {

    @Binds
    @IntoSet
    abstract fun bindMcpRuntime(runtime: McpRuntime): Runtime
}
