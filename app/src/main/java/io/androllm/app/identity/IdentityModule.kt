package io.androllm.app.identity

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.app.BuildConfig
import io.androllm.core.network.identity.IdentityApi
import io.androllm.core.network.identity.SyncApi
import io.ktor.client.HttpClient
import javax.inject.Singleton

/**
 * Phase 1 — wires [IdentityApi] to the private backend.
 *
 * Base URL comes from `BuildConfig.BACKEND_BASE_URL`, which is fed by the
 * `ANDROLLM_BACKEND_URL` environment variable / Gradle property at build time
 * (see `app/build.gradle.kts`). Empty means "no backend configured" — the app
 * works fully offline/guest and every [IdentityApi] call returns Unauthorized
 * without hitting the network.
 */
@Module
@InstallIn(SingletonComponent::class)
object IdentityModule {

    @Provides
    @Singleton
    fun provideIdentityApi(
        httpClient: HttpClient,
        tokenProvider: FirebaseIdTokenProvider,
    ): IdentityApi {
        val baseUrl = BuildConfig.BACKEND_BASE_URL.trim()
        return IdentityApi(
            baseUrl = baseUrl.ifBlank { "https://localhost-disabled.invalid" },
            httpClient = httpClient,
            idTokenProvider = { forceRefresh -> tokenProvider.getToken(forceRefresh) },
            isConfigured = baseUrl.isNotBlank(),
        )
    }

    /**
     * Phase 3 (revised) — usage/snapshot ingestion client. Same base URL and
     * token source as [IdentityApi]; callers must gate on
     * `UserProfile.canSyncUsage` (the worker verifies via `GET /me`).
     */
    @Provides
    @Singleton
    fun provideSyncApi(
        httpClient: HttpClient,
        tokenProvider: FirebaseIdTokenProvider,
    ): SyncApi {
        val baseUrl = BuildConfig.BACKEND_BASE_URL.trim()
        return SyncApi(
            baseUrl = baseUrl.ifBlank { "https://localhost-disabled.invalid" },
            httpClient = httpClient,
            idTokenProvider = { forceRefresh -> tokenProvider.getToken(forceRefresh) },
            isConfigured = baseUrl.isNotBlank(),
        )
    }
}
