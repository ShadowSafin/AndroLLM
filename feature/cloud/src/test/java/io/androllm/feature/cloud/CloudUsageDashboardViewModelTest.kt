package io.androllm.feature.cloud

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.androllm.core.cloud.ProviderHealthMonitor
import io.androllm.core.cloud.ProviderManager
import io.androllm.core.cloud.cache.PromptCache
import io.androllm.core.cloud.model.CloudHealth
import io.androllm.core.cloud.model.CloudProvider
import io.androllm.core.cloud.model.CloudSettings
import io.androllm.core.cloud.usage.CloudUsageMeter
import io.androllm.core.cloud.usage.InMemoryCloudUsageStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Dashboard metrics at the ViewModel level: snapshot exposure, filters,
 * cache stats, provider health passthrough and clear/export actions.
 */
class CloudUsageDashboardViewModelTest {

    companion object {
        init {
            // Arms the Gradle-on-Windows worker shutdown watchdog exactly
            // once per test JVM (see TestWorkerShutdownWatchdog).
            TestWorkerShutdownWatchdog.arm()
        }
    }

    @get:Rule
    val instantExecutor = InstantTaskExecutorRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var meter: CloudUsageMeter
    private lateinit var promptCache: PromptCache
    private lateinit var providerManager: ProviderManager
    private lateinit var healthMonitor: ProviderHealthMonitor
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        meter = CloudUsageMeter(InMemoryCloudUsageStore(), persistDebounceMs = 5)
        promptCache = PromptCache(diskFile = null)
        providerManager = mockk(relaxed = true)
        healthMonitor = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { context.cacheDir } returns tempFolder.root
        every { context.packageName } returns "io.androllm.test"
        // The real androidx FileProvider walks the provider's XML path
        // metadata via PackageManager + XmlResourceParser. Against relaxed
        // mocks that loop never terminates (next() keeps returning 0), so
        // give it a PackageManager that fails fast — this also exercises
        // the export's "saved to path" fallback branch.
        every { context.packageManager } returns mockk<android.content.pm.PackageManager> {
            every { resolveContentProvider(any<String>(), any<Int>()) } returns null
        }
        every { providerManager.settings } returns flowOf(
            CloudSettings(
                enabled = true,
                providers = listOf(
                    CloudProvider(id = "p1", name = "Primary", baseUrl = "https://x", enabled = true, isDefault = true)
                )
            )
        )
        every { healthMonitor.status } returns MutableStateFlow(
            mapOf("p1" to CloudHealth(reachable = true, alive = true, ready = true, latencyMs = 120))
        )
        coEvery { healthMonitor.checkAll() } returns emptyMap()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): CloudUsageDashboardViewModel =
        CloudUsageDashboardViewModel(
            usageMeter = meter,
            promptCache = promptCache,
            providerManager = providerManager,
            healthMonitor = healthMonitor,
            context = context
        )

    @Test
    fun `recorded usage appears in the dashboard snapshot`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        meter.record(
            meter.buildRecord(
                providerId = "p1", providerName = "Primary", modelId = "openai/gpt-4o",
                inputTokens = 100, outputTokens = 50, latencyMs = 700, firstTokenMs = 150,
                toolCallsCount = 2
            )
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.snapshot)
        assertEquals(1, state.snapshot!!.total.requests)
        assertEquals(150L, state.snapshot!!.total.totalTokens)
        assertEquals(2, state.snapshot!!.total.toolCalls)
        assertEquals("Primary", state.settings.providers.first().name)
        assertEquals(120L, state.health["p1"]?.latencyMs)
    }

    @Test
    fun `cache diagnostics flow into the dashboard`() = runTest {
        val key = "prefix:p1:m:h"
        promptCache.put(
            io.androllm.core.cloud.cache.PromptCacheEntry(
                key = key, fingerprint = "h", providerId = "p1", modelId = "m",
                kind = io.androllm.core.cloud.cache.PromptCacheContentKind.CHAT_PREFIX,
                estimatedTokens = 100, contentChars = 400, createdAtMs = 0, lastUsedAtMs = 0
            )
        )
        promptCache.noteMiss(key)
        promptCache.noteHit(key, savedTokens = 100, latencySavedMs = 40, costSavedMicros = 25)

        val vm = createViewModel()
        advanceUntilIdle()

        val cache = vm.uiState.value.cacheStats
        assertEquals(1, cache.hits)
        assertEquals(1, cache.misses)
        assertEquals(0.5f, cache.hitRate, 0.001f)
        assertEquals(100L, cache.savedTokens)
        assertEquals(25L, cache.estimatedCostSavedMicros)
    }

    @Test
    fun `provider filter narrows the snapshot`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        meter.record(
            meter.buildRecord(providerId = "p1", providerName = "Primary", modelId = "m1", inputTokens = 10, outputTokens = 5)
        )
        meter.record(
            meter.buildRecord(providerId = "p2", providerName = "Other", modelId = "m1", inputTokens = 20, outputTokens = 10)
        )
        advanceUntilIdle()

        vm.setProviderFilter("p1")
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.snapshot!!.filtered.requests)
        assertEquals("p1", vm.uiState.value.providerFilter)
    }

    @Test
    fun `clear usage empties snapshot and cache`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        meter.record(
            meter.buildRecord(providerId = "p1", providerName = "Primary", modelId = "m1", inputTokens = 10, outputTokens = 5)
        )
        promptCache.noteMiss("some-key")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.snapshot!!.total.requests)

        vm.clearUsage()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.snapshot!!.total.requests)
        assertEquals(0, vm.uiState.value.cacheStats.lookups)
        assertEquals("Cloud usage data cleared", vm.message.value)
    }

    @Test
    fun `export produces a csv file`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        meter.record(
            meter.buildRecord(providerId = "p1", providerName = "Primary", modelId = "m1", inputTokens = 10, outputTokens = 5)
        )
        advanceUntilIdle()

        vm.exportUsage()
        advanceUntilIdle()

        val exports = File(tempFolder.root, "exports").listFiles()
        assertNotNull(exports)
        assertTrue(exports!!.any { it.name.endsWith(".csv") })
        val csv = exports.first { it.name.endsWith(".csv") }.readText()
        assertTrue(csv.contains("timestamp_iso"))
        assertTrue(csv.contains("m1"))
    }

    @Test
    fun `date range filter excludes old records`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        val now = System.currentTimeMillis()
        meter.record(
            meter.buildRecord(providerId = "p1", providerName = "Primary", modelId = "m1", inputTokens = 1, outputTokens = 1)
                .copy(timestampMs = now - 20L * 24 * 3600 * 1000) // 20 days ago
        )
        meter.record(
            meter.buildRecord(providerId = "p1", providerName = "Primary", modelId = "m1", inputTokens = 2, outputTokens = 2)
        )
        advanceUntilIdle()

        vm.setDateRange(UsageDateRange.WEEK)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.snapshot!!.filtered.requests)
    }
}
