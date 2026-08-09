package io.androllm.core.tools.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun `parses canonical envelope`() {
        val calls = ToolCallParser.parse(
            """{"calls":[{"name":"get_weather","arguments":{"location":"Delhi"}}]}"""
        )
        assertThat(calls).hasSize(1)
        assertThat(calls[0].name).isEqualTo("get_weather")
        assertThat(calls[0].arguments["location"]?.toString()).isEqualTo("\"Delhi\"")
    }

    @Test
    fun `parses bare array`() {
        val calls = ToolCallParser.parse(
            """[{"name":"set_flashlight","arguments":{"on":true}}]"""
        )
        assertThat(calls).hasSize(1)
        assertThat(calls[0].name).isEqualTo("set_flashlight")
    }

    @Test
    fun `parses single object`() {
        val calls = ToolCallParser.parse(
            """{"name":"copy_to_clipboard","arguments":{"text":"hi"}}"""
        )
        assertThat(calls).hasSize(1)
        assertThat(calls[0].name).isEqualTo("copy_to_clipboard")
    }

    @Test
    fun `parses markdown fenced json`() {
        val calls = ToolCallParser.parse(
            """
            ```json
            {"calls":[{"name":"search_web","arguments":{"query":"nvidia stock"}}]}
            ```
            """.trimIndent()
        )
        assertThat(calls).hasSize(1)
        assertThat(calls[0].name).isEqualTo("search_web")
    }

    @Test
    fun `parses multiple calls in order`() {
        val calls = ToolCallParser.parse(
            """{"calls":[{"name":"a","arguments":{}},{"name":"b","arguments":{"x":1}}]}"""
        )
        assertThat(calls.map { it.name }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `tolerates prose before and after`() {
        val calls = ToolCallParser.parse(
            "I will check the weather.\n{\"calls\":[{\"name\":\"get_weather\",\"arguments\":{\"location\":\"Paris\"}}]}\nThat's all."
        )
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `tolerates truncated json`() {
        val calls = ToolCallParser.parse(
            """{"calls":[{"name":"get_weather","arguments":{"location":"Delhi"}}"""
        )
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `empty output yields no calls`() {
        assertThat(ToolCallParser.parse("").isEmpty()).isTrue()
        assertThat(ToolCallParser.parse("no json here").isEmpty()).isTrue()
        assertThat(ToolCallParser.parse("""{"calls":[]}""").isEmpty()).isTrue()
    }

    @Test
    fun `assigns stable ids when missing`() {
        val calls = ToolCallParser.parse("""[{"name":"a","arguments":{}}]""")
        assertThat(calls[0].id.isNotBlank()).isTrue()
    }
}
