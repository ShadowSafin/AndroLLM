package io.androllm.core.memory.intelligence

import io.androllm.core.cloud.CloudGateway
import io.androllm.core.common.Result
import io.androllm.core.common.getOrNull
import io.androllm.core.memory.model.ExtractedMemory
import io.androllm.core.memory.model.MemoryExchange
import io.androllm.core.memory.model.MemorySettings
import io.androllm.core.memory.util.MemoryLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Memory intelligence must be provider-INDEPENDENT: the active chat model
 * never decides whether memory works. The router picks the ACTIVE provider
 * (cloud when configured, local otherwise) and falls back to local when the
 * cloud call fails — memory always keeps working.
 */
class RoutingMemoryIntelligenceTest {

    private val cloud: CloudMemoryIntelligence = mockk()
    private val local: LocalMemoryIntelligence = mockk()
    private val cloudGateway: CloudGateway = mockk()
    private val router = RoutingMemoryIntelligence(cloud, local, cloudGateway, MemoryLogger())

    private val exchange = MemoryExchange("c1", "I love Kotlin", "That's great!", emptyList(), 1)
    private val settings = MemorySettings(enabled = true)

    @Test
    fun `uses cloud intelligence when cloud mode is configured`() = runTest {
        coEvery { cloudGateway.isConfigured() } returns true
        coEvery { cloud.extract(exchange, settings) } returns Result.Success(
            listOf(ExtractedMemory("User loves Kotlin", io.androllm.core.memory.MemoryCategory.SKILLS, 3))
        )
        coEvery { cloud.summarize(any(), any(), any()) } returns Result.Success("rolling summary")

        val memories = router.extract(exchange, settings).getOrNull().orEmpty()
        val summary = router.summarize("c1", null, emptyList()).getOrNull()

        assertEquals(1, memories.size)
        assertEquals("rolling summary", summary)
        coVerify(exactly = 1) { cloud.extract(exchange, settings) }
        coVerify(exactly = 0) { local.extract(any(), any()) }
        coVerify(exactly = 1) { cloud.summarize("c1", null, emptyList()) }
    }

    @Test
    fun `uses local intelligence when cloud mode is off`() = runTest {
        coEvery { cloudGateway.isConfigured() } returns false
        coEvery { local.extract(exchange, settings) } returns Result.Success(emptyList())
        coEvery { local.summarize(any(), any(), any()) } returns Result.Success("local summary")

        val memories = router.extract(exchange, settings).getOrNull().orEmpty()
        val summary = router.summarize("c1", null, emptyList()).getOrNull()

        assertEquals(0, memories.size)
        assertEquals("local summary", summary)
        coVerify(exactly = 0) { cloud.extract(any(), any()) }
        coVerify(exactly = 1) { local.extract(exchange, settings) }
    }

    @Test
    fun `falls back to local when the cloud call fails`() = runTest {
        coEvery { cloudGateway.isConfigured() } returns true
        coEvery { cloud.extract(exchange, settings) } returns Result.error("cloud down")
        coEvery { cloud.summarize(any(), any(), any()) } returns Result.error("cloud down")
        coEvery { local.extract(exchange, settings) } returns Result.Success(
            listOf(ExtractedMemory("User loves Kotlin", io.androllm.core.memory.MemoryCategory.SKILLS, 2))
        )
        coEvery { local.summarize(any(), any(), any()) } returns Result.Success("local summary")

        val memories = router.extract(exchange, settings).getOrNull().orEmpty()
        val summary = router.summarize("c1", null, emptyList()).getOrNull()

        assertEquals(1, memories.size)
        assertEquals("local summary", summary)
        coVerify(exactly = 1) { local.extract(exchange, settings) }
        coVerify(exactly = 1) { local.summarize("c1", null, emptyList()) }
    }

    @Test
    fun `extraction disabled short-circuits before any provider call`() = runTest {
        val disabled = settings.copy(extractionEnabled = false)
        coEvery { cloudGateway.isConfigured() } returns true

        val memories = router.extract(exchange, disabled).getOrNull().orEmpty()

        assertEquals(0, memories.size)
        coVerify(exactly = 0) { cloud.extract(any(), any()) }
        coVerify(exactly = 0) { local.extract(any(), any()) }
    }
}