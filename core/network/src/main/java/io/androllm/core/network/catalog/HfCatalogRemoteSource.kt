package io.androllm.core.network.catalog

import io.androllm.core.common.Result
import io.androllm.core.models.catalog.CatalogRemoteSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the latest model catalog JSON from a remote endpoint, trying the
 * primary catalog host first and falling back to the project's GitHub copy.
 *
 * Future-proofing: the catalog is plain metadata, so new official LiteRT-LM
 * models can be added server-side without an app update. When every endpoint
 * is unreachable (offline, or the file has not been published yet),
 * [CatalogRepository] simply keeps the bundled catalog asset.
 */
@Singleton
class HfCatalogRemoteSource @Inject constructor(
    private val client: HttpClient
) : CatalogRemoteSource {

    override suspend fun fetchCatalogJson(): Result<String> = io.androllm.core.common.runCatching {
        var lastError: Throwable? = null
        for (url in CATALOG_URLS) {
            try {
                val response = client.get(url)
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Catalog fetch failed: HTTP ${response.status.value}")
                }
                val body = response.body<String>()
                if (body.isNotBlank()) return@runCatching body
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("No catalog endpoint available")
    }

    companion object {
        /**
         * Endpoints tried in order. The primary host can publish new LiteRT
         * models at any time; the GitHub copy mirrors this repo's bundled
         * catalog so a working copy always exists after a release.
         */
        val CATALOG_URLS: List<String> = listOf(
            "https://models.androllm.com/catalog.json",
            "https://raw.githubusercontent.com/ShadowSafin/AndroLLM/main/core/models/src/main/assets/catalog_v1.json"
        )
    }
}