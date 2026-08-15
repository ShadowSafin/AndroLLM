package io.androllm.core.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RuntimeRegistry contract tests. The critical one is [a throwing runtime is
 * isolated]: the registry must never let one broken runtime hide or disable
 * the others — "image runtime crashes → chat keeps working" is enforced here.
 */
class RuntimeRegistryTest {

    private class FakeRuntime(
        override val id: String,
        override val displayName: String = id,
        override val category: RuntimeCategory = RuntimeCategory.TOOLS,
        override val description: String = "",
        private val result: RuntimeStatus = RuntimeStatus(true, "ok"),
        private val throwOnStatus: Boolean = false
    ) : Runtime {
        override suspend fun status(): RuntimeStatus {
            if (throwOnStatus) throw IllegalStateException("boom")
            return result
        }
    }

    @Test
    fun `runtimes are listed in stable id order`() = runTest {
        val registry = RuntimeRegistry(
            setOf(
                FakeRuntime("voice"),
                FakeRuntime("gguf"),
                FakeRuntime("image"),
                FakeRuntime("cloud"),
                FakeRuntime("tools"),
                FakeRuntime("mcp"),
                FakeRuntime("automation")
            )
        )
        assertThat(registry.all.map { it.id })
            .containsExactly("automation", "cloud", "gguf", "image", "mcp", "tools", "voice")
            .inOrder()
        assertThat(registry.size).isEqualTo(7)
    }

    @Test
    fun `byId and byCategory find the right runtime`() = runTest {
        val registry = RuntimeRegistry(
            setOf(
                FakeRuntime("image", category = RuntimeCategory.IMAGE),
                FakeRuntime("tools", category = RuntimeCategory.TOOLS),
                FakeRuntime("voice", category = RuntimeCategory.VOICE)
            )
        )
        assertThat(registry.byId("image")?.id).isEqualTo("image")
        assertThat(registry.byId("missing")).isNull()
        assertThat(registry.byCategory(RuntimeCategory.IMAGE).map { it.id }).containsExactly("image")
        assertThat(registry.byCategory(RuntimeCategory.CLOUD)).isEmpty()
    }

    @Test
    fun `a throwing runtime is isolated and reported as failed`() = runTest {
        val registry = RuntimeRegistry(
            setOf(
                FakeRuntime("broken", throwOnStatus = true),
                FakeRuntime("fine", result = RuntimeStatus(true, "ready"))
            )
        )

        // Must not throw even though one runtime throws internally.
        val statuses = registry.statuses()
        val byId = statuses.associate { it.first.id to it.second }

        assertThat(statuses).hasSize(2)
        assertThat(byId["fine"]!!.available).isTrue()
        assertThat(byId["fine"]!!.summary).isEqualTo("ready")

        assertThat(byId["broken"]!!.available).isFalse()
        assertThat(byId["broken"]!!.summary).isEqualTo("Status check failed")
        assertThat(byId["broken"]!!.detail).contains("boom")
    }

    @Test
    fun `statuses covers every registered runtime`() = runTest {
        val registry = RuntimeRegistry(setOf(FakeRuntime("a"), FakeRuntime("b"), FakeRuntime("c")))
        val statuses = registry.statuses()
        assertThat(statuses).hasSize(3)
        assertThat(statuses.map { it.first.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `unavailable runtime reports its own status without touching others`() = runTest {
        val registry = RuntimeRegistry(
            setOf(
                FakeRuntime("cloud", result = RuntimeStatus(false, "No cloud provider configured")),
                FakeRuntime("gguf", result = RuntimeStatus(true, "Ready — model loaded"))
            )
        )
        val byId = registry.statuses().associate { it.first.id to it.second }
        assertThat(byId["cloud"]!!.available).isFalse()
        assertThat(byId["gguf"]!!.available).isTrue()
    }
}
