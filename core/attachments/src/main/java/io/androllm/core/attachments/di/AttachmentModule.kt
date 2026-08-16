package io.androllm.core.attachments.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt wiring for the attachments module. [AttachmentProcessor],
 * [AttachmentSettingsStore] and [ParserRegistry] are all @Singleton classes
 * with @Inject constructors, so no explicit bindings are needed — this module
 * exists as the module's Hilt entry point.
 */
@Module
@InstallIn(SingletonComponent::class)
object AttachmentModule
