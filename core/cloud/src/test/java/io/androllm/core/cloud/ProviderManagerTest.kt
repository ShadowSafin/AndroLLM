package io.androllm.core.cloud

import io.androllm.core.cloud.model.CloudProvider
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.network.LiteLLMClient
import io.androllm.core.cloud.security.KeyCipher
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderManagerTest {

    private val store = FakeCloudSettingsRepository()
    private val client = mockk<LiteLLMClient>(relaxed = true)
    private val manager = ProviderManager(store, client, FakeKeyCipher())

    @Before
    fun setUp() = runTest {
        // Reset the store between tests.
        store.update { CloudSettings() }
    }

    @Test
    fun `addProvider encrypts the key and becomes default when first`() = runTest {
        val provider = manager.addProvider(
            name = "Self-hosted",
            baseUrl = "https://proxy.example.com",
            apiKey = "sk-secret-123"
        )

        assertEquals("enc(sk-secret-123)", provider.apiKeyEncrypted)
        assertEquals(provider.id, store.current().defaultProviderId)
        assertEquals("sk-secret-123", manager.getApiKey(provider))
    }

    @Test
    fun `addProvider rejects invalid urls`() = runTest {
        kotlin.runCatching {
            manager.addProvider(name = "Bad", baseUrl = "ftp://nope")
        }.onFailure {
            assertTrue(it is IllegalArgumentException)
            return@runTest
        }
        assertTrue("expected validation failure", false)
    }

    @Test
    fun `updateProvider keeps key when blank`() = runTest {
        val provider = manager.addProvider(name = "P", baseUrl = "https://a.example.com", apiKey = "sk-1")
        manager.updateProvider(id = provider.id, name = "P2", baseUrl = "https://b.example.com", apiKey = null)

        val updated = store.current().providers.single()
        assertEquals("P2", updated.name)
        assertEquals("https://b.example.com", updated.baseUrl)
        assertEquals("enc(sk-1)", updated.apiKeyEncrypted)
        assertEquals("sk-1", manager.getApiKey(updated))
    }

    @Test
    fun `deleteProvider clears default when deleted provider was default`() = runTest {
        val provider = manager.addProvider(name = "P", baseUrl = "https://a.example.com")
        manager.setDefaultProvider(provider.id)
        manager.deleteProvider(provider.id)

        val settings = store.current()
        assertTrue(settings.providers.isEmpty())
        assertEquals("", settings.defaultProviderId)
    }

    @Test
    fun `setEnabled toggles and disabled providers are skipped by resolver`() = runTest {
        val a = manager.addProvider(name = "A", baseUrl = "https://a.example.com")
        val b = manager.addProvider(name = "B", baseUrl = "https://b.example.com")
        manager.setDefaultProvider(a.id)
        manager.setEnabled(a.id, false)

        val resolved = manager.resolveProvider()
        assertEquals(b.id, resolved?.id)
    }

    @Test
    fun `resolveModel falls back to first cached model`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com")
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == provider.id) it.copy(modelIds = listOf("openai/gpt-4o", "gemini/gemini-pro")) else it
                }
            )
        }
        val fresh = store.current().providers.single()
        assertEquals("openai/gpt-4o", manager.resolveModel(fresh))
    }

    @Test
    fun `resolveModel prefers explicit default`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com")
        manager.setDefaultModel("anthropic/claude-3-5-sonnet")
        assertEquals("anthropic/claude-3-5-sonnet", manager.resolveModel(provider))
    }

    @Test
    fun `testConnection persists latency models and quota on success`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com", apiKey = "sk-1")
        coEvery { client.health(any(), any(), any()) } returns io.androllm.core.cloud.model.CloudHealth(
            reachable = true, alive = true, ready = true, latencyMs = 37
        )
        coEvery { client.listModelsWithQuota(any(), any(), any()) } returns (
            listOf(
                io.androllm.core.cloud.model.CloudModelInfo(id = "openai/gpt-4o"),
                io.androllm.core.cloud.model.CloudModelInfo(id = "anthropic/claude-3-5-sonnet")
            ) to io.androllm.core.cloud.model.CloudQuota(
                remainingRequests = 10, remainingTokens = 500, lastStatus = 200
            )
        )

        val result = manager.testConnection(provider.id)

        assertTrue(result.ok)
        assertEquals(2, result.modelCount)
        assertEquals(37L, result.latencyMs)
        val stored = store.current().providers.single()
        assertEquals(37L, stored.latencyMs)
        assertEquals(listOf("openai/gpt-4o", "anthropic/claude-3-5-sonnet"), stored.modelIds)
        assertEquals(10L, stored.quota?.remainingRequests)
    }

    @Test
    fun `testConnection records failure message`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com")
        val failure = io.androllm.core.cloud.model.CloudException("Connection refused")
        coEvery { client.health(any(), any(), any()) } throws failure
        coEvery { client.listModelsWithQuota(any(), any(), any()) } throws failure

        val result = manager.testConnection(provider.id)

        assertFalse(result.ok)
        assertEquals("Connection refused", result.error)
        assertEquals("Connection refused", store.current().providers.single().lastError)
    }

    @Test
    fun `testConnection passes for OpenAI-compatible router without health endpoints`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://router.example.com", apiKey = "sk-1")
        coEvery { client.health(any(), any(), any()) } returns io.androllm.core.cloud.model.CloudHealth(
            reachable = true, alive = false, ready = false, latencyMs = 12, supportsHealthEndpoints = false
        )
        coEvery { client.listModelsWithQuota(any(), any(), any()) } returns (
            listOf(
                io.androllm.core.cloud.model.CloudModelInfo(id = "gpt-4o")
            ) to io.androllm.core.cloud.model.CloudQuota()
        )

        val result = manager.testConnection(provider.id)

        assertTrue(result.ok)
        assertEquals(1, result.modelCount)
        assertEquals("", result.error)
    }

    @Test
    fun `testConnection reports auth failure when models endpoint rejects the key`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://router.example.com", apiKey = "sk-wrong")
        coEvery { client.health(any(), any(), any()) } returns io.androllm.core.cloud.model.CloudHealth(
            reachable = true, alive = false, ready = false, latencyMs = 5, supportsHealthEndpoints = false
        )
        coEvery { client.listModelsWithQuota(any(), any(), any()) } throws
            io.androllm.core.cloud.model.CloudException("Authentication failed (check the API key)", statusCode = 401)

        val result = manager.testConnection(provider.id)

        assertFalse(result.ok)
        assertTrue(result.error.contains("Authentication failed"))
    }

    @Test
    fun `addCustomModel encrypts key and persists under provider`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com", apiKey = "sk-1")
        val custom = manager.addCustomModel(
            providerId = provider.id,
            modelName = "My R1",
            modelId = "deepseek/deepseek-r1",
            apiBaseUrl = "https://models.example.com",
            apiKey = "sk-custom-9",
            apiKeyHeader = "X-Model-Key",
            tags = listOf("fast", "reasoning")
        )

        assertEquals("enc(sk-custom-9)", custom.apiKeyEncrypted)
        val stored = store.current().providers.single().customModels.single()
        assertEquals("My R1", stored.modelName)
        assertEquals("deepseek/deepseek-r1", stored.modelId)
        assertEquals("X-Model-Key", stored.apiKeyHeader)
        assertEquals(listOf("fast", "reasoning"), stored.tags)
    }

    @Test
    fun `deleteCustomModel removes model and clears it as default`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com")
        val custom = manager.addCustomModel(providerId = provider.id, modelName = "R1", modelId = "deepseek/deepseek-r1")
        manager.setDefaultModel(custom.modelId)
        manager.deleteCustomModel(provider.id, custom.id)

        val settings = store.current()
        assertTrue(settings.providers.single().customModels.isEmpty())
        assertEquals("", settings.defaultModelId)
    }

    @Test
    fun `resolveChatModel applies custom model overrides`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com", apiKey = "sk-1")
        val custom = manager.addCustomModel(
            providerId = provider.id,
            modelName = "My R1",
            modelId = "deepseek/deepseek-r1",
            apiBaseUrl = "https://models.example.com",
            apiKey = "sk-custom-9"
        )
        manager.setDefaultModel(custom.modelId)

        val resolved = manager.resolveChatModel()

        assertEquals("deepseek/deepseek-r1", resolved?.modelId)
        assertTrue(resolved!!.isCustom)
        assertEquals("https://models.example.com", resolved.overrides.apiBaseUrl)
        assertEquals("sk-custom-9", resolved.overrides.apiKey)
        assertEquals("My R1", resolved.displayName)
    }

    @Test
    fun `resolveChatModel falls back to provider key for discovered models`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com", apiKey = "sk-1")
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == provider.id) it.copy(modelIds = listOf("openai/gpt-4o")) else it
                }
            )
        }

        val resolved = manager.resolveChatModel()

        assertEquals("openai/gpt-4o", resolved?.modelId)
        assertFalse(resolved!!.isCustom)
        assertEquals("sk-1", resolved.apiKey)
        assertNull(resolved.overrides.apiBaseUrl)
    }

    @Test
    fun `cloudModels merges discovered and custom models with UI state`() = runTest {
        val provider = manager.addProvider(name = "A", baseUrl = "https://a.example.com")
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == provider.id) it.copy(modelIds = listOf("openai/gpt-4o"), modelContextWindows = mapOf("openai/gpt-4o" to 128_000L)) else it
                },
                favoriteModelIds = setOf("openai/gpt-4o")
            )
        }
        manager.addCustomModel(providerId = provider.id, modelName = "R1", modelId = "deepseek/deepseek-r1")

        val models = manager.cloudModels().first()

        val discovered = models.first { it.id == "openai/gpt-4o" }
        assertFalse(discovered.isCustom)
        assertTrue(discovered.isFavorite)
        assertEquals(128_000L, discovered.contextWindow)
        val custom = models.first { it.id == "deepseek/deepseek-r1" }
        assertTrue(custom.isCustom)
        assertEquals("R1", custom.displayName)
    }
}

/** In-memory [CloudSettingsRepository] for tests. */
private class FakeCloudSettingsRepository : CloudSettingsRepository {
    private val flow = MutableStateFlow(CloudSettings())

    override val settings: Flow<CloudSettings> = flow

    override suspend fun current(): CloudSettings = flow.value

    override suspend fun update(transform: (CloudSettings) -> CloudSettings) {
        flow.value = transform(flow.value)
    }
}

/** Reversible trivial cipher for tests — mirrors the KeyCipher contract. */
private class FakeKeyCipher : KeyCipher {
    override fun encrypt(plaintext: String): String = if (plaintext.isEmpty()) "" else "enc($plaintext)"

    override fun decrypt(ciphertext: String): String =
        if (ciphertext.isEmpty()) "" else ciphertext.removePrefix("enc(").removeSuffix(")")

    override fun delete() = Unit
}
