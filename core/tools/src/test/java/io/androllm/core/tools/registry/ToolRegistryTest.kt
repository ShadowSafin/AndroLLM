package io.androllm.core.tools.registry

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ToolRegistryTest {

    private class FakeTool(name: String) : Tool {
        override val spec = ToolSpec(name = name, description = "test tool")
        override suspend fun execute(arguments: JsonObject) = ToolResult.Success("ok")
    }

    @Test
    fun `register and get by name`() {
        val registry = ToolRegistry()
        val tool = FakeTool("get_weather")
        assertThat(registry.register(tool)).isTrue()
        assertThat(registry.get("get_weather")).isSameInstanceAs(tool)
        assertThat(registry.contains("get_weather")).isTrue()
        assertThat(registry.size()).isEqualTo(1)
    }

    @Test
    fun `re-register replaces`() {
        val registry = ToolRegistry()
        registry.register(FakeTool("a"))
        assertThat(registry.register(FakeTool("a"))).isFalse()
        assertThat(registry.size()).isEqualTo(1)
    }

    @Test
    fun `unregister removes`() {
        val registry = ToolRegistry()
        registry.register(FakeTool("a"))
        assertThat(registry.unregister("a")).isNotNull()
        assertThat(registry.get("a")).isNull()
        assertThat(registry.unregister("missing")).isNull()
    }

    @Test
    fun `registerAll adds every tool`() {
        val registry = ToolRegistry()
        registry.registerAll(listOf(FakeTool("a"), FakeTool("b"), FakeTool("c")))
        assertThat(registry.all().map { it.spec.name }).containsExactly("a", "b", "c")
    }
}
