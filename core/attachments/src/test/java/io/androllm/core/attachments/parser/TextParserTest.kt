package io.androllm.core.attachments.parser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, content: String): File =
        tmp.newFolder("docs").apply { mkdirs() }.let { dir ->
            File(dir, name).apply { writeText(content) }
        }

    @Test
    fun `txt parser collapses whitespace and trims lines`() {
        val file = write("notes.txt", "  Hello    world  \n\n  Second   line   here  \n")
        val parsed = TxtParser().parse(file)
        assertEquals("Hello world\nSecond line here", parsed.text)
        assertEquals(1, parsed.pageCount)
    }

    @Test
    fun `markdown parser strips inline markers but keeps headings`() {
        val file = write(
            "doc.md",
            "# Title\n\nSome **bold** and `code` and [link](http://x).\n\n## Sub\n\nText under sub."
        )
        val parsed = MarkdownParser().parse(file)
        assertTrue(parsed.text.startsWith("Title"))
        assertEquals(2, parsed.headings.size)
        assertEquals(1, parsed.headings[0].level)
        assertEquals("Title", parsed.headings[0].text)
        assertEquals(2, parsed.headings[1].level)
        assertEquals("Sub", parsed.headings[1].text)
    }

    @Test
    fun `csv parser is quote-aware and reflows records`() {
        val file = write("data.csv", "name,role\n\"Ada, Lovelace\",Engineer\nGrace,Poet\n")
        val parsed = CsvParser().parse(file)
        assertTrue(parsed.text.contains("Ada, Lovelace | Engineer"))
        assertTrue(parsed.text.contains("Grace | Poet"))
    }

    @Test
    fun `json parser flattens keys and values into readable text`() {
        val file = write("data.json", "{\"name\": \"AndroLLM\", \"version\": 1}")
        val parsed = JsonParser().parse(file)
        assertTrue(parsed.text.contains("name: AndroLLM"))
        assertTrue(parsed.text.contains("version: 1"))
    }

    @Test
    fun `html parser strips tags and preserves heading outline`() {
        val file = write(
            "page.html",
            "<html><body><h1>Intro</h1><p>Hello <b>world</b></p><script>bad()</script><h2>Detail</h2><p>More</p></body></html>"
        )
        val parsed = HtmlParser().parse(file)
        assertTrue(parsed.text.contains("Intro"))
        assertTrue(parsed.text.contains("Hello world"))
        assertTrue(!parsed.text.contains("bad()"))
        assertEquals(2, parsed.headings.size)
    }

    @Test
    fun `unsupported extension is rejected`() {
        val file = write("x.xyz", "hello")
        assertEquals(false, TxtParser().supports(file))
        assertEquals(false, MarkdownParser().supports(file))
    }
}
