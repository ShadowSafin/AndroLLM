package io.androllm.core.cloud

import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.network.LiteLLMClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthMonitorTest {

    private val client = mockk<LiteLLMClient>(relaxed = true)

    @Test
    fun `checkAll probes only enabled providers and publishes results`() = runTest {
        val store = InMemorySettingsRepository(CloudSettings())
        val enabled = storeSeedProvider(store, id = "p1", enabled = true)
        val disabled = storeSeedProvider(store, id = "p2", enabled = false)
        val manager = ProviderManager(store, client, FakeKeyCipher2())
        coEvery { client.health(enabled, any(), any()) } returns CloudHealth(
            reachable = true, alive = true, ready = true, latencyMs = 21
        )
        coEvery { client.health(disabled, any(), any()) } throws IllegalStateException("must not be probed")

        val monitor = ProviderHealthMonitor(manager, client)
        val results = monitor.checkAll(store.current())

        assertEquals(1, results.size)
        assertTrue(results.containsKey("p1"))
        assertTrue(!results.containsKey("p2"))
        assertEquals(21L, monitor.status.value["p1"]?.latencyMs)
    }

    @Test
    fun `check returns null for unknown provider`() = runTest {
        val manager = ProviderManager(InMemorySettingsRepository(CloudSettings()), client, FakeKeyCipher2())
        val monitor = ProviderHealthMonitor(manager, client)

        assertEquals(null, monitor.check("nope"))
    }

    @Test
    fun `status updates after single check`() = runTest {
        val store = InMemorySettingsRepository(CloudSettings())
        val provider = storeSeedProvider(store, id = "p1", enabled = true)
        val manager = ProviderManager(store, client, FakeKeyCipher2())
        coEvery { client.health(provider, any(), any()) } returns CloudHealth(
            reachable = true, alive = true, ready = true, latencyMs = 5
        )
        val monitor = ProviderHealthMonitor(manager, client)

        val health = monitor.check("p1")

        assertEquals(5L, health?.latencyMs)
        assertEquals(5L, monitor.status.first()["p1"]?.latencyMs)
    }

    private suspend fun storeSeedProvider(
        store: InMemorySettingsRepository,
        id: String,
        enabled: Boolean
    ) = io.androllm.core.cloud.model.CloudProvider(
        id = id,
        name = id,
        baseUrl = "https://proxy.example.com",
        enabled = enabled
    ).also { provider ->
        store.update { settings -> settings.copy(providers = settings.providers + provider) }
    }
}

/** In-memory repository for monitor tests (kept distinct from the manager test fake). */
private class InMemorySettingsRepository(initial: CloudSettings) : CloudSettingsRepository {
    private var state = initial

    override val settings: kotlinx.coroutines.flow.Flow<CloudSettings> =
        kotlinx.coroutines.flow.flowOf(state)

    override suspend fun current(): CloudSettings = state

    override suspend fun update(transform: (CloudSettings) -> CloudSettings) {
        state = transform(state)
    }
}

private class FakeKeyCipher2 : io.androllm.core.cloud.security.KeyCipher {
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(ciphertext: String): String = ciphertext
    override fun delete() = Unit
}
