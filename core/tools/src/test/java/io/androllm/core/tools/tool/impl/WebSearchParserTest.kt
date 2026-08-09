package io.androllm.core.tools.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchParserTest {

    // ── Provider 1: DuckDuckGo Instant Answer JSON ──────────────────────────

    @Test
    fun `instant answer parses abstract`() {
        val body = """
            {"Heading":"Android 17","AbstractText":"Android 17 is a fictional character in the Dragon Ball franchise.","AbstractURL":"https://en.wikipedia.org/wiki/Android_17"}
        """.trimIndent()
        val results = WebSearchParser.parseInstantAnswer(body)
        assertEquals(1, results.size)
        assertEquals("Android 17", results[0].title)
        assertTrue(results[0].url.contains("wikipedia"))
        assertTrue(results[0].snippet.contains("Dragon Ball"))
    }

    @Test
    fun `instant answer parses related topics`() {
        val body = """
            {"Heading":"","RelatedTopics":[
              {"Text":"NVIDIA - Wikipedia","FirstURL":"https://en.wikipedia.org/wiki/NVIDIA"},
              {"Topics":[{"Text":"NVIDIA news - Example","FirstURL":"https://example.com/news"}]}
            ]}
        """.trimIndent()
        val results = WebSearchParser.parseInstantAnswer(body)
        assertEquals(2, results.size)
        assertEquals("NVIDIA", results[0].title)
        assertEquals("NVIDIA news", results[1].title)
    }

    @Test
    fun `instant answer survives malformed json`() {
        assertTrue(WebSearchParser.parseInstantAnswer("this is not json").isEmpty())
        assertTrue(WebSearchParser.parseInstantAnswer("").isEmpty())
    }

    // ── Provider 2: DuckDuckGo HTML results ─────────────────────────────────

    @Test
    fun `ddg html parses titles snippets and unwraps uddg urls`() {
        val html = """
            <div class="result">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fandroid-17&amp;rut=abc">Android 17 <b>Dragon Ball</b></a>
              <a class="result__snippet">A <b>character</b> from the series with &amp; many appearances</a>
            </div>
        """.trimIndent()
        val results = WebSearchParser.parseDdgHtml(html)
        assertEquals(1, results.size)
        assertEquals("Android 17 Dragon Ball", results[0].title)
        assertEquals("A character from the series with & many appearances", results[0].snippet)
        assertEquals("https://example.com/android-17", results[0].url)
    }

    @Test
    fun `ddg html keeps plain urls as-is`() {
        val html = """
            <div class="result">
              <a class="result__a" href="https://news.example.com/story">Story Title</a>
              <a class="result__snippet">A snippet</a>
            </div>
        """.trimIndent()
        val results = WebSearchParser.parseDdgHtml(html)
        assertEquals(1, results.size)
        assertEquals("https://news.example.com/story", results[0].url)
    }

    @Test
    fun `ddg html skips empty titles`() {
        val html = """
            <div class="result">
              <a class="result__a" href="https://x.example.com">   </a>
            </div>
        """.trimIndent()
        assertTrue(WebSearchParser.parseDdgHtml(html).isEmpty())
    }

    // ── Provider 3: Bing HTML ──────────────────────────────────────────────

    @Test
    fun `bing html parses b_algo blocks with direct urls`() {
        val html = """
            <ol id="b_results">
              <li class="b_algo">
                <h2 class=""><a href="https://developer.android.com/about/versions/17/get" h="ID=SERP,5088.2">Android 17 <b>release notes</b></a></h2>
                <div class="b_caption"><p class="b_lineclamp3">Find out what's new in Android <b>17</b>.</p></div>
              </li>
              <li class="b_algo">
                <h2 class=""><a href="https://www.androidauthority.com/android-17-3561251/" h="ID=SERP,5100.2">Android 17: Everything we know</a></h2>
                <div class="b_caption"><p class="b_lineclamp2">A deep dive into the upcoming release.</p></div>
              </li>
            </ol>
        """.trimIndent()
        val results = WebSearchParser.parseBingHtml(html)
        assertEquals(2, results.size)
        assertEquals("Android 17 release notes", results[0].title)
        assertEquals("https://developer.android.com/about/versions/17/get", results[0].url)
        assertEquals("Find out what's new in Android 17.", results[0].snippet)
        assertEquals("https://www.androidauthority.com/android-17-3561251/", results[1].url)
    }

    @Test
    fun `bing html skips javascript links and empty titles`() {
        val html = """
            <li class="b_algo">
              <h2 class=""><a href="javascript:void(0)">Bad link</a></h2>
            </li>
            <li class="b_algo">
              <h2 class=""><a href="https://ok.example.com">   </a></h2>
            </li>
            <li class="b_algo">
              <h2 class=""><a href="https://good.example.com/page">Good page</a></h2>
              <div class="b_caption"><p class="b_lineclamp3">A useful snippet</p></div>
            </li>
        """.trimIndent()
        val results = WebSearchParser.parseBingHtml(html)
        assertEquals(1, results.size)
        assertEquals("Good page", results[0].title)
        assertEquals("https://good.example.com/page", results[0].url)
    }

    @Test
    fun `bing html survives non result pages`() {
        assertTrue(WebSearchParser.parseBingHtml("<html><body>anomaly detected</body></html>").isEmpty())
    }
}
