package io.androllm.core.tools.prompt

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.planner.ToolPlanner
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ToolPromptBuilderTest {

    private val weather = ToolSpec(
        name = "get_weather",
        description = "Get current weather for a city.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties", buildJsonObject {
                    put("city", buildJsonObject { put("type", "string") })
                    put("units", buildJsonObject { put("type", "string") })
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("city")) })
        },
        category = ToolCategory.INFORMATION
    )

    private val flashlight = ToolSpec(
        name = "set_flashlight",
        description = "Turn the flashlight on or off.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties", buildJsonObject {
                    put("on", buildJsonObject { put("type", "boolean") })
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("on")) })
        },
        category = ToolCategory.DEVICE
    )

    @Test
    fun `advertisement returns null when the pipeline reports no tools`() = runTest {
        val planner = mockk<ToolPlanner>()
        coEvery { planner.routedTools(any(), any()) } returns emptyList()
        val builder = ToolPromptBuilder(planner)

        assertThat(builder.advertisement()).isNull()
    }

    @Test
    fun `render lists every tool with description arguments and example`() {
        val builder = ToolPromptBuilder(mockk(relaxed = true))
        val text = builder.render(listOf(weather, flashlight))

        // The model is told it HAS tools and must never claim otherwise.
        assertThat(text).contains("you HAVE access to tools")
        assertThat(text).contains("never claim you lack access")
        assertThat(text).contains("AVAILABLE TOOLS")

        // weather: description, argument types and a required-field example.
        assertThat(text).contains("- get_weather")
        assertThat(text).contains("Get current weather for a city.")
        assertThat(text).contains("city (string)")
        assertThat(text).contains("units (string)")
        assertThat(text).contains("example: {\"city\": \"value\"}")

        // flashlight: boolean placeholder in the example.
        assertThat(text).contains("- set_flashlight")
        assertThat(text).contains("on (boolean)")
        assertThat(text).contains("example: {\"on\": true}")
    }

    @Test
    fun `advertisement wraps the rendered block`() = runTest {
        val planner = mockk<ToolPlanner>()
        coEvery { planner.routedTools(any(), any()) } returns listOf(weather)
        val builder = ToolPromptBuilder(planner)

        val ad = builder.advertisement()
        assertThat(ad).isNotNull()
        assertThat(ad).contains("- get_weather")
    }
}
