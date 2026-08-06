package io.androllm.core.memory.extraction

import io.androllm.core.memory.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionJsonParserTest {

    @Test
    fun `parses clean JSON`() {
        val raw = """
            {"memories":[
              {"content":"User prefers dark mode","category":"PREFERENCES","importance":3,"tags":["ui"],"project":null},
              {"content":"Working on AndroLLM","category":"PROJECTS","importance":4,"tags":[],"project":"AndroLLM"}
            ]}
        """.trimIndent()
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(2, items.size)
        assertEquals(MemoryCategory.PREFERENCES, items[0].category)
        assertEquals(listOf("ui"), items[0].tags)
        assertEquals("AndroLLM", items[1].projectName)
    }

    @Test
    fun `parses markdown fenced JSON`() {
        val raw = """
            Here is the result:
            ```json
            {"memories":[{"content":"User knows Kotlin","category":"SKILLS","importance":2}]}
            ```
            Hope that helps!
        """.trimIndent()
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(1, items.size)
        assertEquals(MemoryCategory.SKILLS, items[0].category)
    }

    @Test
    fun `normalizes category name variants`() {
        val raw = """
            {"memories":[
              {"content":"A","category":"programming_languages"},
              {"content":"B","category":"Programming Languages"},
              {"content":"C","category":"BOGUS"},
              {"content":"D","category":"PREFERENCES"}
            ]}
        """.trimIndent()
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(MemoryCategory.PROGRAMMING_LANGUAGES, items[0].category)
        assertEquals(MemoryCategory.PROGRAMMING_LANGUAGES, items[1].category)
        assertEquals(MemoryCategory.CUSTOM, items[2].category)
        assertEquals(MemoryCategory.PREFERENCES, items[3].category)
    }

    @Test
    fun `recovers content from truncated output`() {
        val raw = """{"memories":[{"content":"User is learning Jetpack Compose","category":"GOALS","imp"""
        val items = ExtractionJsonParser.parse(raw)
        assertTrue(items.isNotEmpty())
        assertTrue(items[0].content.contains("Jetpack Compose"))
    }

    @Test
    fun `clamps importance to valid range`() {
        val raw = """{"memories":[{"content":"A","importance":99},{"content":"B","importance":-5}]}"""
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(5, items[0].importance)
        assertEquals(1, items[1].importance)
    }

    @Test
    fun `deduplicates identical content`() {
        val raw = """{"memories":[{"content":"Same fact","category":"CUSTOM"},{"content":"Same fact","category":"CUSTOM"}]}"""
        val items = ExtractionJsonParser.parse(raw)
        assertEquals(1, items.size)
    }

    @Test
    fun `empty output yields empty list`() {
        assertTrue(ExtractionJsonParser.parse("").isEmpty())
        assertTrue(ExtractionJsonParser.parse("no json here").isEmpty())
        assertTrue(ExtractionJsonParser.parse("""{"memories":[]}""").isEmpty())
    }

    @Test
    fun `ignores blank and overlong content`() {
        val long = "x".repeat(900)
        val raw = """{"memories":[{"content":"  "},{"content":"$long"}]}"""
        val items = ExtractionJsonParser.parse(raw)
        assertTrue(items.isEmpty())
    }
}
