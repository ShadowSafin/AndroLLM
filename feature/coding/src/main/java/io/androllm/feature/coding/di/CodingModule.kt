package io.androllm.feature.coding.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.androllm.core.cloud.CloudGateway
import io.androllm.feature.coding.agent.CodingAvailabilityChecker
import io.androllm.feature.coding.agent.CodingCloudClient
import io.androllm.feature.coding.agent.GatewayCodingCloudClient
import io.androllm.feature.coding.environment.ApkAddonInstaller
import io.androllm.feature.coding.environment.BackgroundServiceManager
import io.androllm.feature.coding.environment.DelegatingAddonInstaller
import io.androllm.feature.coding.environment.EnvironmentManager
import io.androllm.feature.coding.environment.LinuxBaseManager
import io.androllm.feature.coding.environment.SimulatedAddonInstaller
import io.androllm.feature.coding.environment.proot.AndroidProotFiles
import io.androllm.feature.coding.environment.proot.DebianRootfsDownloader
import io.androllm.feature.coding.environment.proot.ProotFiles
import io.androllm.feature.coding.environment.proot.ProotShellBackend
import io.androllm.feature.coding.tools.CodingToolRegistry
import io.androllm.feature.coding.workspace.AndroidWorkspaceRootProvider
import io.androllm.feature.coding.workspace.ChatTranscriptStore
import io.androllm.feature.coding.workspace.DataStoreWorkspaceStore
import io.androllm.feature.coding.workspace.FileChatTranscriptStore
import io.androllm.feature.coding.workspace.WorkspaceManager
import io.androllm.feature.coding.workspace.WorkspaceRootProvider
import io.androllm.feature.coding.workspace.WorkspaceStore
import java.io.File
import javax.inject.Singleton

/**
 * Hilt bindings for the coding feature. Everything the coding agent needs is
 * provided here so the feature stays self-contained; none of these leak into the
 * global device-tool registry or the normal-chat graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object CodingModule {

    @Provides
    @Singleton
    fun provideWorkspaceRootProvider(
        @ApplicationContext context: Context
    ): WorkspaceRootProvider = AndroidWorkspaceRootProvider(context)

    @Provides
    @Singleton
    fun provideWorkspaceStore(
        @ApplicationContext context: Context
    ): WorkspaceStore = DataStoreWorkspaceStore(context)

    @Provides
    @Singleton
    fun provideWorkspaceManager(
        rootProvider: WorkspaceRootProvider,
        store: WorkspaceStore
    ): WorkspaceManager = WorkspaceManager(rootProvider, store)

    @Provides
    @Singleton
    fun provideChatTranscriptStore(
        @ApplicationContext context: Context
    ): ChatTranscriptStore = FileChatTranscriptStore(File(context.filesDir, "coding-sessions"))

    @Provides
    @Singleton
    fun provideProotFiles(@ApplicationContext context: Context): ProotFiles =
        AndroidProotFiles(context).get()

    @Provides
    @Singleton
    fun provideLinuxBaseManager(
        @ApplicationContext context: Context,
        prootFiles: ProotFiles
    ): LinuxBaseManager = LinuxBaseManager(
        files = prootFiles,
        tarballs = DebianRootfsDownloader.forApp(context)
    )

    @Provides
    @Singleton
    fun provideProotShellBackend(
        prootFiles: ProotFiles,
        baseManager: LinuxBaseManager
    ): ProotShellBackend = ProotShellBackend(
        files = prootFiles,
        baseReady = { baseManager.isInstalled() }
    )

    @Provides
    @Singleton
    fun provideBackgroundServiceManager(
        @ApplicationContext context: Context
    ): BackgroundServiceManager = BackgroundServiceManager(
        servicesDir = File(File(context.filesDir, "coding-env"), "services")
    )

    @Provides
    @Singleton
    fun provideEnvironmentManager(
        @ApplicationContext context: Context,
        baseManager: LinuxBaseManager,
        prootBackend: ProotShellBackend
    ): EnvironmentManager = EnvironmentManager(
        envRoot = { File(context.filesDir, "coding-env") },
        installer = DelegatingAddonInstaller(
            base = baseManager,
            apk = ApkAddonInstaller(
                base = baseManager,
                shell = prootBackend,
                scratchDir = File(File(context.filesDir, "coding-env"), "tmp")
            ),
            simulated = SimulatedAddonInstaller()
        )
    )

    @Provides
    @Singleton
    fun provideCodingCloudClient(gateway: CloudGateway): CodingCloudClient =
        GatewayCodingCloudClient(gateway)

    @Provides
    @Singleton
    fun provideCodingToolRegistry(): CodingToolRegistry = CodingToolRegistry()

    @Provides
    @Singleton
    fun provideCodingAvailabilityChecker(
        cloudClient: CodingCloudClient,
        workspaceManager: WorkspaceManager
    ): CodingAvailabilityChecker = CodingAvailabilityChecker(cloudClient, workspaceManager)
}
