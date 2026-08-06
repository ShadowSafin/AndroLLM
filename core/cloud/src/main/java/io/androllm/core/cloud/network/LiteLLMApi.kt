package io.androllm.core.cloud.network

import io.androllm.core.cloud.model.CloudChatRequest
import io.androllm.core.cloud.model.CloudChatResponse
import io.androllm.core.cloud.model.CloudEmbeddingRequest
import io.androllm.core.cloud.model.CloudEmbeddingResponse
import io.androllm.core.cloud.model.CloudModelInfoList
import io.androllm.core.cloud.model.CloudModelList
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Retrofit surface over the LiteLLM proxy's OpenAI-compatible REST API.
 *
 * All endpoints use fully-qualified [Url]s so a single instance serves any
 * number of providers (and per-model alternate servers) without rebuilding
 * the Retrofit stack. Every request carries a [HeaderMap] built from the
 * provider's auth header + extra headers — the values are resolved per call
 * and never logged.
 */
interface LiteLLMApi {

    /** `POST /v1/chat/completions` with SSE streaming. */
    @POST
    @Streaming
    suspend fun streamChat(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body request: CloudChatRequest
    ): Response<ResponseBody>

    /** `POST /v1/chat/completions` (non-streaming). */
    @POST
    suspend fun chat(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body request: CloudChatRequest
    ): Response<CloudChatResponse>

    /** `POST /v1/embeddings`. */
    @POST
    suspend fun embeddings(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body request: CloudEmbeddingRequest
    ): Response<CloudEmbeddingResponse>

    /** `GET /v1/models` — model discovery. */
    @GET
    suspend fun listModels(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<CloudModelList>

    /** `GET /v1/model/info` — richer model metadata (context window, ...). */
    @GET
    suspend fun modelInfo(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<CloudModelInfoList>

    /** Generic probe for `/health/liveliness` and `/health/readiness`. */
    @GET
    suspend fun probe(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<ResponseBody>
}
