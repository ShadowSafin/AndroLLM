package io.androllm.core.cloud

import io.androllm.core.cloud.model.CloudCustomModel
import io.androllm.core.cloud.model.CloudModelOverrides
import io.androllm.core.cloud.model.CloudModelProvider
import io.androllm.core.cloud.model.CloudProvider
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.model.ConnectionTestResult
import io.androllm.core.cloud.model.CloudException
import io.androllm.core.cloud.model.ResolvedCloudModel
import io.androllm.core.cloud.network.LiteLLMClient
import io.androllm.core.cloud.security.KeyCipher
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Owns the provider lifecycle: creation/editing/deletion, enable/disable,
 * default selection, favorites, connection testing, and model discovery.
 *
 * API keys are encrypted with [KeyCipher] the moment they are received and
 * only ever read back by decrypting on demand.
 */
@Singleton
class ProviderManager @Inject constructor(
    private val store: CloudSettingsRepository,
    private val client: LiteLLMClient,
    private val keyCipher: KeyCipher
) {

    /** Observable snapshot of all cloud settings. */
    val settings: Flow<CloudSettings> = store.settings

    suspend fun current(): CloudSettings = store.current()

    /** All selectable models (discovered + custom) merged with provider/UI state. */
    fun cloudModels(): Flow<List<CloudModelProvider>> = store.settings.map { settings ->
        settings.providers.flatMap { provider ->
            val discovered = provider.modelIds.map { id ->
                CloudModelProvider(
                    id = id,
                    providerId = provider.id,
                    providerName = provider.name,
                    isCustom = false,
                    contextWindow = provider.modelContextWindows[id],
                    maxOutputTokens = provider.modelMaxOutputTokens[id],
                    isFavorite = id in settings.favoriteModelIds,
                    isDefault = settings.defaultProviderId == provider.id && settings.defaultModelId == id,
                    enabled = provider.enabled
                )
            }
            val custom = provider.customModels.map { model ->
                CloudModelProvider(
                    id = model.modelId,
                    providerId = provider.id,
                    providerName = provider.name,
                    displayName = model.modelName,
                    isCustom = true,
                    description = model.description,
                    tags = model.tags,
                    contextWindow = provider.modelContextWindows[model.modelId],
                    maxOutputTokens = provider.modelMaxOutputTokens[model.modelId],
                    isFavorite = model.modelId in settings.favoriteModelIds,
                    isDefault = settings.defaultProviderId == provider.id && settings.defaultModelId == model.modelId,
                    enabled = provider.enabled
                )
            }
            discovered + custom
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    /**
     * Creates a provider. [apiKey] is plaintext from the user and is
     * encrypted before persistence.
     */
    suspend fun addProvider(
        name: String,
        baseUrl: String,
        apiKey: String = "",
        apiKeyHeader: String = "Authorization",
        extraHeaders: Map<String, String> = emptyMap(),
        description: String = "",
        tags: List<String> = emptyList()
    ): CloudProvider {
        validate(name, baseUrl)
        val provider = CloudProvider(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKeyEncrypted = keyCipher.encrypt(apiKey.trim()),
            apiKeyHeader = apiKeyHeader.ifBlank { "Authorization" },
            extraHeaders = extraHeaders.filterValues { it.isNotBlank() },
            description = description.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotBlank() },
            enabled = true
        )
        store.update { settings ->
            val isFirst = settings.providers.isEmpty()
            settings.copy(
                providers = settings.providers + provider,
                defaultProviderId = if (isFirst) provider.id else settings.defaultProviderId
            )
        }
        return provider
    }

    suspend fun updateProvider(
        id: String,
        name: String,
        baseUrl: String,
        apiKey: String? = null, // null = keep existing key
        apiKeyHeader: String = "Authorization",
        extraHeaders: Map<String, String> = emptyMap(),
        description: String = "",
        tags: List<String> = emptyList()
    ): CloudProvider? {
        validate(name, baseUrl)
        var updated: CloudProvider? = null
        store.update { settings ->
            val existing = settings.providers.find { it.id == id } ?: return@update settings
            updated = existing.copy(
                name = name.trim(),
                baseUrl = baseUrl.trim().trimEnd('/'),
                apiKeyEncrypted = if (apiKey.isNullOrBlank()) existing.apiKeyEncrypted else keyCipher.encrypt(apiKey.trim()),
                apiKeyHeader = apiKeyHeader.ifBlank { "Authorization" },
                extraHeaders = extraHeaders.filterValues { it.isNotBlank() },
                description = description.trim(),
                tags = tags.map { it.trim() }.filter { it.isNotBlank() }
            )
            settings.copy(
                providers = settings.providers.map { if (it.id == id) updated!! else it }
            )
        }
        return updated
    }

    suspend fun deleteProvider(id: String) {
        store.update { settings ->
            settings.copy(
                providers = settings.providers.filterNot { it.id == id },
                defaultProviderId = if (settings.defaultProviderId == id) "" else settings.defaultProviderId,
                defaultModelId = if (settings.defaultProviderId == id) "" else settings.defaultModelId
            )
        }
    }

    // ── Custom models ─────────────────────────────────────────────────────

    /**
     * Adds a user-defined model to a provider. [apiKey] is plaintext from the
     * user and is encrypted before persistence.
     */
    suspend fun addCustomModel(
        providerId: String,
        modelName: String,
        modelId: String,
        apiBaseUrl: String? = null,
        apiKey: String = "",
        apiKeyHeader: String = "Authorization",
        extraHeaders: Map<String, String> = emptyMap(),
        description: String = "",
        tags: List<String> = emptyList()
    ): CloudCustomModel {
        require(modelName.isNotBlank()) { "Model name is required" }
        require(modelId.isNotBlank()) { "LiteLLM model identifier is required" }
        val custom = CloudCustomModel(
            id = UUID.randomUUID().toString(),
            modelName = modelName.trim(),
            modelId = modelId.trim(),
            apiBaseUrl = apiBaseUrl?.trim()?.takeIf { it.isNotBlank() },
            apiKeyEncrypted = keyCipher.encrypt(apiKey.trim()),
            apiKeyHeader = apiKeyHeader.ifBlank { "Authorization" },
            extraHeaders = extraHeaders.filterValues { it.isNotBlank() },
            description = description.trim(),
            tags = tags.map { it.trim() }.filter { it.isNotBlank() }
        )
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == providerId) it.copy(customModels = it.customModels + custom) else it
                }
            )
        }
        return custom
    }

    /** Updates a custom model. [apiKey] null keeps the existing encrypted key. */
    suspend fun updateCustomModel(
        providerId: String,
        customModelId: String,
        modelName: String,
        modelId: String,
        apiBaseUrl: String? = null,
        apiKey: String? = null,
        apiKeyHeader: String = "Authorization",
        extraHeaders: Map<String, String> = emptyMap(),
        description: String = "",
        tags: List<String> = emptyList()
    ): CloudCustomModel? {
        require(modelName.isNotBlank()) { "Model name is required" }
        require(modelId.isNotBlank()) { "LiteLLM model identifier is required" }
        var updated: CloudCustomModel? = null
        store.update { settings ->
            val provider = settings.providers.find { it.id == providerId } ?: return@update settings
            val existing = provider.customModels.find { it.id == customModelId } ?: return@update settings
            updated = existing.copy(
                modelName = modelName.trim(),
                modelId = modelId.trim(),
                apiBaseUrl = apiBaseUrl?.trim()?.takeIf { it.isNotBlank() },
                apiKeyEncrypted = if (apiKey.isNullOrBlank()) existing.apiKeyEncrypted else keyCipher.encrypt(apiKey.trim()),
                apiKeyHeader = apiKeyHeader.ifBlank { "Authorization" },
                extraHeaders = extraHeaders.filterValues { it.isNotBlank() },
                description = description.trim(),
                tags = tags.map { it.trim() }.filter { it.isNotBlank() }
            )
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == providerId) {
                        it.copy(customModels = it.customModels.map { m -> if (m.id == customModelId) updated!! else m })
                    } else it
                }
            )
        }
        return updated
    }

    /** Removes a custom model and clears it from the default selection. */
    suspend fun deleteCustomModel(providerId: String, customModelId: String) {
        store.update { settings ->
            val provider = settings.providers.find { it.id == providerId }
            val removedModelId = provider?.customModels?.find { it.id == customModelId }?.modelId
            settings.copy(
                defaultModelId = if (settings.defaultModelId == removedModelId) "" else settings.defaultModelId,
                providers = settings.providers.map {
                    if (it.id == providerId) {
                        it.copy(customModels = it.customModels.filterNot { m -> m.id == customModelId })
                    } else it
                }
            )
        }
    }

    // ── Toggles ───────────────────────────────────────────────────────────

    suspend fun setEnabled(id: String, enabled: Boolean) {
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map { if (it.id == id) it.copy(enabled = enabled) else it }
            )
        }
    }

    suspend fun setDefaultProvider(id: String) {
        store.update { settings ->
            val provider = settings.providers.find { it.id == id } ?: return@update settings
            // Drop a default model that belongs to the previous provider when
            // the new one's discovered list is known and doesn't contain it.
            val staleModel = settings.defaultModelId.isNotBlank() &&
                provider.modelIds.isNotEmpty() &&
                settings.defaultModelId !in provider.modelIds
            settings.copy(
                defaultProviderId = id,
                defaultModelId = if (staleModel) "" else settings.defaultModelId,
                providers = settings.providers.map { it.copy(isDefault = it.id == id) }
            )
        }
    }

    suspend fun setDefaultModel(modelId: String) {
        store.update { settings -> settings.copy(defaultModelId = modelId) }
    }

    suspend fun toggleFavorite(modelId: String) {
        store.update { settings ->
            val favorites = settings.favoriteModelIds
            settings.copy(
                favoriteModelIds = if (modelId in favorites) favorites - modelId else favorites + modelId
            )
        }
    }

    /** Master toggle for cloud chat mode. */
    suspend fun setCloudModeEnabled(enabled: Boolean) {
        store.update { settings -> settings.copy(enabled = enabled) }
    }

    // ── Secrets ───────────────────────────────────────────────────────────

    /** Decrypts a provider's stored API key (empty when none was saved). */
    suspend fun getApiKey(provider: CloudProvider): String =
        runCatching { keyCipher.decrypt(provider.apiKeyEncrypted) }.getOrDefault("")

    // ── Discovery & health ────────────────────────────────────────────────

    /**
     * Tests a provider: health probes + model discovery, then persists results.
     *
     * For LiteLLM proxies the health/readiness probes are authoritative.
     * OpenAI-compatible routers that don't implement them fall back to
     * `/v1/models`: a successful listing proves connectivity AND auth, so a
     * correct key no longer fails the test just because the router has no
     * health endpoint.
     */
    suspend fun testConnection(id: String): ConnectionTestResult {
        val provider = store.current().providers.find { it.id == id }
            ?: throw CloudException("Provider not found")
        val apiKey = getApiKey(provider)

        val health = runCatching { client.health(provider, apiKey) }.getOrNull()
        val modelsResult = runCatching { client.listModelsWithQuota(provider, apiKey) }
        val models = modelsResult.getOrNull()?.first.orEmpty()
        val quota = modelsResult.getOrNull()?.second
        val modelsError = modelsResult.exceptionOrNull()

        val supportsHealth = health?.supportsHealthEndpoints == true
        val ok = if (supportsHealth) {
            health?.reachable == true && health.ready
        } else {
            modelsError == null && health?.reachable != false
        }

        val error = buildString {
            if (modelsError != null) append(modelsError.message)
            else if (supportsHealth && health?.reachable == true && !health.ready) {
                append("Proxy reachable but not ready")
            } else if (health?.reachable == false) {
                append("Connection failed — proxy unreachable")
            }
        }.let { it.ifBlank { "" } }

        persistTestResult(id, health?.latencyMs ?: 0L, error, quota, if (modelsError == null) models.map { it.id } else null)
        return ConnectionTestResult(
            providerId = id,
            ok = ok,
            latencyMs = health?.latencyMs ?: 0,
            alive = health?.alive == true,
            ready = health?.ready == true,
            modelCount = models.size,
            error = error
        )
    }

    /** Refreshes the cached model list for a provider. Returns the model count. */
    suspend fun refreshModels(id: String): Int {
        val provider = store.current().providers.find { it.id == id }
            ?: throw CloudException("Provider not found")
        val apiKey = getApiKey(provider)
        val (models, quota) = client.listModelsWithQuota(provider, apiKey)
        val modelIds = models.map { it.id }.distinct().sorted()
        val metadata = runCatching { client.listModelMetadata(provider, apiKey) }
            .getOrDefault(io.androllm.core.cloud.model.ModelMetadata())
        persistTestResult(id, provider.latencyMs, provider.lastError, quota, modelIds, metadata)
        return modelIds.size
    }

    private suspend fun persistTestResult(
        id: String,
        latencyMs: Long,
        error: String,
        quota: io.androllm.core.cloud.model.CloudQuota?,
        modelIds: List<String>?,
        metadata: io.androllm.core.cloud.model.ModelMetadata = io.androllm.core.cloud.model.ModelMetadata()
    ) {
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id != id) it else it.copy(
                        latencyMs = latencyMs,
                        lastError = error,
                        quota = quota,
                        lastCheckedAt = System.currentTimeMillis(),
                        modelIds = modelIds ?: it.modelIds,
                        modelContextWindows = if (metadata.contextWindows.isEmpty()) it.modelContextWindows else metadata.contextWindows,
                        modelMaxOutputTokens = if (metadata.maxOutputTokens.isEmpty()) it.modelMaxOutputTokens else metadata.maxOutputTokens
                    )
                }
            )
        }
    }

    // ── Resolution (used by the chat gateway) ─────────────────────────────

    /** The active provider: explicit default, else first enabled provider. */
    suspend fun resolveProvider(): CloudProvider? {
        val settings = store.current()
        settings.providers.find { it.id == settings.defaultProviderId && it.enabled }
            ?.let { return it }
        return settings.providers.firstOrNull { it.enabled }
    }

    /** The model to use: explicit default, else the provider's first cached model. */
    suspend fun resolveModel(provider: CloudProvider): String? {
        val defaultModel = store.current().defaultModelId
        if (defaultModel.isNotBlank()) return defaultModel
        return provider.modelIds.firstOrNull()
    }

    /**
     * Best-effort maximum output tokens for [modelId] (the resolved default
     * when null), from /v1/model/info metadata. Null when unknown — callers
     * should then omit `max_tokens` and let the provider pick its own max.
     */
    suspend fun maxOutputTokensFor(modelId: String? = null): Long? {
        val resolved = resolveChatModel(modelId) ?: return null
        return resolved.provider.modelMaxOutputTokens[resolved.modelId]
    }

    /**
     * Fully resolves the active chat target (provider + model + model-scoped
     * overrides). [modelId] overrides the persisted default selection. Custom
     * models contribute their own LiteLLM server, key and headers; discovered
     * models use the provider's settings.
     */
    suspend fun resolveChatModel(modelId: String? = null): ResolvedCloudModel? {
        val settings = store.current()
        val provider = if (modelId.isNullOrBlank()) {
            resolveProvider()
        } else {
            settings.providers.firstOrNull { it.enabled &&
                (modelId in it.modelIds || it.customModels.any { m -> m.modelId == modelId }) }
        } ?: return null
        val resolvedModelId = if (modelId.isNullOrBlank()) {
            resolveModel(provider) ?: return null
        } else modelId
        return resolveOnProviderInternal(provider, resolvedModelId)
    }

    /**
     * Resolves a chat target on a SPECIFIC provider — used by the gateway's
     * fallback chain when the primary provider fails before producing any
     * output. [preferredModelId] is used when that provider offers it (same
     * model across providers keeps the conversation coherent); otherwise the
     * provider's own default model is selected.
     */
    suspend fun resolveOnProvider(providerId: String, preferredModelId: String? = null): ResolvedCloudModel? {
        val settings = store.current()
        val provider = settings.providers.find { it.id == providerId && it.enabled } ?: return null
        val modelId = preferredModelId
            ?.takeIf { id -> id in provider.modelIds || provider.customModels.any { m -> m.modelId == id } }
            ?: resolveModel(provider)
            ?: return null
        return resolveOnProviderInternal(provider, modelId)
    }

    /** Shared key/override resolution for [resolveChatModel] and [resolveOnProvider]. */
    private suspend fun resolveOnProviderInternal(provider: CloudProvider, resolvedModelId: String): ResolvedCloudModel {
        val apiKey = getApiKey(provider)
        val custom = provider.customModels.firstOrNull { it.modelId == resolvedModelId }
        if (custom != null) {
            val customKey = if (custom.apiKeyEncrypted.isNotBlank()) {
                runCatching { keyCipher.decrypt(custom.apiKeyEncrypted) }.getOrDefault("")
            } else ""
            return ResolvedCloudModel(
                provider = provider,
                modelId = resolvedModelId,
                displayName = custom.modelName.ifBlank { resolvedModelId },
                isCustom = true,
                apiKey = customKey.ifBlank { apiKey },
                overrides = CloudModelOverrides(
                    apiBaseUrl = custom.apiBaseUrl,
                    apiKey = customKey,
                    apiKeyHeader = custom.apiKeyHeader,
                    extraHeaders = custom.extraHeaders
                )
            )
        }
        return ResolvedCloudModel(
            provider = provider,
            modelId = resolvedModelId,
            displayName = resolvedModelId,
            isCustom = false,
            apiKey = apiKey,
            overrides = CloudModelOverrides()
        )
    }

    private fun validate(name: String, baseUrl: String) {
        require(name.isNotBlank()) { "Provider name is required" }
        val url = baseUrl.trim()
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Base URL must start with http:// or https://"
        }
        require(!url.substringAfter("://").isNullOrBlank()) { "Base URL is invalid" }
    }
}
