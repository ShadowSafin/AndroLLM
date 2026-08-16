package io.androllm.core.tools.coordinator

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ToolLoopGuardTest {

    private fun args(expression: String): JsonObject = buildJsonObject {
        put("expression", expression)
    }

    private fun success(): ToolResult = ToolResult.Success("ok")
    private fun failure(retryable: Boolean = true): ToolResult =
        ToolResult.Failure("boom", retryable = retryable)

    @Test
    fun `identical call with same arguments is never re-run`() {
        val guard = ToolLoopGuard()
        assertThat(guard.canExecute("calculate", args("23 * 48"))).isTrue()
        guard.record("calculate", args("23 * 48"), success())
        // Same tool + same args again → blocked (dedupe).
        assertThat(guard.canExecute("calculate", args("23 * 48"))).isFalse()
        assertThat(guard.blockedThisTurn).isTrue()
        // Same tool, DIFFERENT args → still allowed.
        assertThat(guard.canExecute("calculate", args("23 * 49"))).isTrue()
    }

    @Test
    fun `consecutive calls to the same tool are capped at two`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        assertThat(guard.canExecute("calculate", args("2 + 2"))).isTrue()
        guard.record("calculate", args("2 + 2"), success())
        // Third consecutive calculate → blocked.
        assertThat(guard.canExecute("calculate", args("3 + 3"))).isFalse()
        // Interleaving another tool resets the consecutive counter.
        guard.record("search_web", buildJsonObject { put("query", "news") }, success())
        assertThat(guard.canExecute("calculate", args("3 + 3"))).isTrue()
    }

    @Test
    fun `total calls are capped at five`() {
        val guard = ToolLoopGuard(maxTotalCalls = 5)
        repeat(5) { i ->
            assertThat(guard.canExecute("tool_$i", buildJsonObject { put("n", i) })).isTrue()
            guard.record("tool_$i", buildJsonObject { put("n", i) }, success())
        }
        assertThat(guard.canExecute("tool_6", buildJsonObject { put("n", 6) })).isFalse()
        assertThat(guard.stopReason()).contains("limit reached")
    }

    @Test
    fun `non-retryable failure disables the tool for the rest of the turn`() {
        val guard = ToolLoopGuard()
        guard.record("get_battery", JsonObject(emptyMap()), failure(retryable = false))
        // Even with fresh arguments, the tool is disabled.
        assertThat(guard.canExecute("get_battery", JsonObject(emptyMap()))).isFalse()
        assertThat(guard.isDisabled("get_battery")).isTrue()
    }

    @Test
    fun `two failures of the same tool disable it`() {
        val guard = ToolLoopGuard()
        guard.record("search_web", buildJsonObject { put("query", "a") }, failure())
        guard.record("search_web", buildJsonObject { put("query", "b") }, failure())
        assertThat(guard.isDisabled("search_web")).isTrue()
    }

    @Test
    fun `successful calls do not disable the tool`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        assertThat(guard.isDisabled("calculate")).isFalse()
    }

    @Test
    fun `stopReason reports the consecutive-tool cap message`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        guard.record("calculate", args("2 + 2"), success())
        assertThat(guard.canExecute("calculate", args("3 + 3"))).isFalse()
        val reason = guard.stopReason()
        assertThat(reason).isNotNull()
        assertThat(reason).contains("calculate")
        assertThat(reason).contains("Continue reasoning without further tool calls")
    }

    @Test
    fun `no blocking means no stop reason`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        assertThat(guard.stopReason()).isNull()
    }
}
