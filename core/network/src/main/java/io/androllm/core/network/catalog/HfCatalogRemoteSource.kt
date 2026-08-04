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
 * Fetches the latest model catalog JSON. The URL points at the catalog file in the
 * project's GitHub repository; when the file has not been pushed yet (or the device
 * is offline) the fetch fails and [CatalogRepository] simply keeps the bundled catalog.
 */
@Singleton
class HfCatalogRemoteSource @Inject constructor(
    private val client: HttpClient
) : CatalogRemoteSource {

    override suspend fun fetchCatalogJson(): Result<String> = io.androllm.core.common.runCatching {
        val response = client.get(CATALOG_URL)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Catalog fetch failed: HTTP ${response.status.value}")
        }
        response.body<String>()
    }

    companion object {
        const val CATALOG_URL =
            "https://raw.githubusercontent.com/ShadowSafin/AndroLLM/main/core/models/src/main/assets/catalog_v1.json"
    }
}
