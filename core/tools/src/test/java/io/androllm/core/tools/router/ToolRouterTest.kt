package io.androllm.core.tools.router

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ToolRouterTest {

    private val router = ToolRouter()

    private fun tool(
        name: String,
        permission: ToolPermission? = null,
        category: ToolCategory = ToolCategory.INFORMATION,
        tasks: List<String> = emptyList()
    ): Tool = object : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "Tool $name",
            permission = permission,
            category = category,
            supportedTasks = tasks
        )
        override suspend fun execute(arguments: JsonObject) = ToolResult.Success("ok")
    }

    private val allTools: List<ToolSpec> = listOf(
        tool("calculate", ToolPermission.CALCULATOR, tasks = listOf("math", "calculate")),
        tool("convert_units", ToolPermission.CALCULATOR),
        tool("get_battery", ToolPermission.DEVICE, ToolCategory.DEVICE, tasks = listOf("battery")),
        tool("get_device_info", ToolPermission.DEVICE, tasks = listOf("device info")),
        tool("search_web", ToolPermission.SEARCH, tasks = listOf("search", "news")),
        tool("get_weather", ToolPermission.WEATHER, tasks = listOf("weather")),
        tool("send_sms", ToolPermission.SMS, ToolCategory.COMMUNICATION),
        tool("make_call", ToolPermission.CALLS, ToolCategory.COMMUNICATION),
        tool("list_downloads", ToolPermission.FILES, ToolCategory.PRODUCTIVITY)
    ).map { it.spec }

    // ── Spec test cases ────────────────────────────────────────────────────

    @Test
    fun `uploaded JSON log error question exposes NO tools`() {
        val routed = router.route(
            "Are there any errors in this log?",
            hasAttachments = true,
            enabledTools = allTools
        )
        assertThat(routed.intent).isEqualTo(ToolIntent.ATTACHMENT)
        assertThat(routed.specs).isEmpty()
    }

    @Test
    fun `uploaded PDF summarize question exposes NO tools`() {
        val routed = router.route(
            "Summarize this PDF.",
            hasAttachments = true,
            enabledTools = allTools
        )
        assertThat(routed.intent).isEqualTo(ToolIntent.ATTACHMENT)
        assertThat(routed.specs).isEmpty()
    }

    @Test
    fun `uploaded DOCX question exposes NO tools`() {
        val routed = router.route(
            "What does this document say about the budget?",
            hasAttachments = true,
            enabledTools = allTools
        )
        assertThat(routed.intent).isEqualTo(ToolIntent.ATTACHMENT)
        assertThat(routed.specs).isEmpty()
    }

    @Test
    fun `arithmetic question exposes calculator only`() {
        val routed = router.route("What is 23 x 48?", hasAttachments = false, enabledTools = allTools)
        assertThat(routed.intent).isEqualTo(ToolIntent.MATH)
        assertThat(routed.specs.map { it.name }).contains("calculate")
        assertThat(routed.specs.map { it.name }).doesNotContain("search_web")
        assertThat(routed.specs.map { it.name }).doesNotContain("get_battery")
    }

    @Test
    fun `battery question exposes device tools only`() {
        val routed = router.route(
            "What is my battery percentage?",
            hasAttachments = false,
            enabledTools = allTools
        )
        assertThat(routed.intent).isEqualTo(ToolIntent.DEVICE)
        assertThat(routed.specs.map { it.name }).contains("get_battery")
        assertThat(routed.specs.map { it.name }).doesNotContain("calculate")
        assertThat(routed.specs.map { it.name }).doesNotContain("send_sms")
    }

    @Test
    fun `hello exposes NO tools`() {
        val routed = router.route("Hello", hasAttachments = false, enabledTools = allTools)
        assertThat(routed.intent).isEqualTo(ToolIntent.NO_TOOLS)
        assertThat(routed.specs).isEmpty()
    }

    @Test
    fun `write a poem exposes NO tools`() {
        val routed = router.route("Write a poem about the ocean.", hasAttachments = false, enabledTools = allTools)
        assertThat(routed.intent).isEqualTo(ToolIntent.NO_TOOLS)
        assertThat(routed.specs).isEmpty()
    }

    @Test
    fun `weather question exposes web and weather tools`() {
        val routed = router.route(
            "What is the weather in London?",
            hasAttachments = false,
            enabledTools = allTools
        )
        assertThat(routed.intent).isEqualTo(ToolIntent.WEB)
        assertThat(routed.specs.map { it.name }).contains("get_weather")
    }

    @Test
    fun `send a text routes to communication tools`() {
        val routed = router.route("Send a text to mom", hasAttachments = false, enabledTools = allTools)
        assertThat(routed.intent).isEqualTo(ToolIntent.COMMUNICATION)
        assertThat(routed.specs.map { it.name }).contains("send_sms")
        assertThat(routed.specs.map { it.name }).doesNotContain("calculate")
    }

    @Test
    fun `attachment present but unrelated math question routes to MATH not ATTACHMENT`() {
        val routed = router.route("What is 2 + 2?", hasAttachments = true, enabledTools = allTools)
        assertThat(routed.intent).isEqualTo(ToolIntent.MATH)
        assertThat(routed.specs.map { it.name }).contains("calculate")
    }

    @Test
    fun `general question falls back to the full set`() {
        val routed = router.route("Can you help me plan my day?", hasAttachments = false, enabledTools = allTools)
        assertThat(routed.intent).isEqualTo(ToolIntent.GENERAL)
        assertThat(routed.specs).hasSize(allTools.size)
    }

    // ── Confidence (spec: per-tool confidence, highest wins) ───────────────

    @Test
    fun `calculator confidence is near zero for a log question`() {
        val calc = allTools.first { it.name == "calculate" }
        val logQuery = "What errors are in this JSON log?"
        assertThat(router.confidence(calc, logQuery)).isLessThan(0.5f)
        val battery = allTools.first { it.name == "get_battery" }
        val batteryQuery = "What is my battery percentage?"
        assertThat(router.confidence(battery, batteryQuery))
            .isGreaterThan(router.confidence(calc, batteryQuery))
    }

    @Test
    fun `confidence is zero for unrelated tools`() {
        val calc = allTools.first { it.name == "calculate" }
        assertThat(router.confidence(calc, "Send a text to mom")).isEqualTo(0f)
    }
}
