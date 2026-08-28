package io.androllm.feature.coding.agent

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.cloud.model.CloudChatMessage
import io.androllm.core.cloud.model.CloudGenerationConfig
import io.androllm.core.cloud.model.CloudStreamEvent
import io.androllm.core.cloud.model.CloudTool
import kotlinx.coroutines.flow.Flow

/**
 * Cloud boundary for the coding agent, abstracted so the agent loop runs on the
 * JVM in tests (fake client emitting scripted events) while production wraps the
 * real [CloudGateway]. Routing through the gateway means every coding request is
 * automatically covered by the cloud pipeline: usage tracking, prompt caching and
 * provider fallback — the "integrate with cloud + usage tracking" requirement.
 */
interface CodingCloudClient {
    /** True when a cloud provider + model is configured and enabled. */
    suspend fun isConfigured(): Boolean

    /** Best-effort max output tokens for the active model (null = unknown). */
    suspend fun maxOutputTokens(): Long?

    /** Human-readable label for the active cloud model (for the header chip). */
    suspend fun activeModelLabel(): String

    /** Streams a completion with the coding [tools] advertised. */
    fun stream(
        messages: List<CloudChatMessage>,
        tools: List<CloudTool>,
        sessionId: String?,
        maxTokens: Int?
    ): Flow<CloudStreamEvent>
}

/** Production implementation backed by the shared [CloudGateway]. */
class GatewayCodingCloudClient(
    private val gateway: CloudGateway,
    private val temperature: Double = 0.2
) : CodingCloudClient {

    override suspend fun isConfigured(): Boolean =
        runCatching { gateway.isConfigured() }.getOrDefault(false)

    override suspend fun maxOutputTokens(): Long? =
        runCatching { gateway.maxOutputTokensFor() }.getOrNull()

    override suspend fun activeModelLabel(): String =
        runCatching {
            val target = gateway.resolveChatTarget()
            target?.second ?: "Cloud model"
        }.getOrDefault("Cloud model")

    override fun stream(
        messages: List<CloudChatMessage>,
        tools: List<CloudTool>,
        sessionId: String?,
        maxTokens: Int?
    ): Flow<CloudStreamEvent> {
        val config = CloudGenerationConfig(
            temperature = temperature,
            topP = 0.9,
            maxTokens = maxTokens,
            tools = tools
        )
        return gateway.streamChat(
            messages = messages,
            config = config,
            sessionId = sessionId
        )
    }
}
