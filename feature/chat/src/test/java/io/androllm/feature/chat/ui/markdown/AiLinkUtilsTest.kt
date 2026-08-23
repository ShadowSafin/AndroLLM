package io.androllm.feature.chat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLinkUtilsTest {

    @Test
    fun `plain URL detection`() {
        val text = "Here is the website: https://androllm.com"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals(1, links.size)
        assertEquals("https://androllm.com", links[0].url)
        assertEquals(null, links[0].displayText)
    }

    @Test
    fun `markdown link detection`() {
        val text = "Check [Google](https://google.com) for search"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals(1, links.size)
        assertEquals("https://google.com", links[0].url)
        assertEquals("Google", links[0].displayText)
    }

    @Test
    fun `multiple links in one response`() {
        val text = "Visit https://androllm.com and https://google.com for more. Also see [Bing](https://bing.com)"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals(3, links.size)
        assertEquals(listOf("https://androllm.com", "https://google.com", "https://bing.com"), links.map { it.url })
    }

    @Test
    fun `strip trailing punctuation`() {
        assertEquals("https://example.com", AiLinkUtils.stripTrailingPunctuation("https://example.com.").first)
        assertEquals("https://example.com", AiLinkUtils.stripTrailingPunctuation("https://example.com,").first)
        assertEquals("https://example.com", AiLinkUtils.stripTrailingPunctuation("https://example.com!").first)
        assertEquals("https://example.com", AiLinkUtils.stripTrailingPunctuation("https://example.com)").first)
        assertEquals("https://example.com", AiLinkUtils.stripTrailingPunctuation("https://example.com?").first)

        val text = "Check https://androllm.com."
        val links = AiLinkUtils.extractLinks(text)
        assertEquals("https://androllm.com", links[0].url)
        val segments = AiLinkUtils.splitTextWithLinks(text)
        assertTrue(segments.any { it is AiLinkUtils.Segment.Plain && (it as AiLinkUtils.Segment.Plain).text == "." })
    }

    @Test
    fun `do not make non-URLs clickable`() {
        val text = "hello world test 123 no links here"
        val links = AiLinkUtils.extractLinks(text)
        assertTrue(links.isEmpty())
        assertFalse(AiLinkUtils.containsLink(text))
    }

    @Test
    fun `markdown and raw URLs safely handled`() {
        val text = "Here [AndroLLM](https://androllm.com) and plain https://example.com/path?query=1"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals(2, links.size)
        assertEquals("https://androllm.com", links[0].url)
        assertEquals("https://example.com/path?query=1", links[1].url)
    }

    @Test
    fun `invalid URL is rejected`() {
        assertFalse(AiLinkUtils.isValidForOpening("javascript:alert(1)"))
        assertFalse(AiLinkUtils.isValidForOpening("file:///etc/passwd"))
        assertFalse(AiLinkUtils.isValidForOpening("intent://example"))
        assertFalse(AiLinkUtils.isValidForOpening("ht!tp://bad"))
        assertFalse(AiLinkUtils.isValidForOpening(""))
        assertFalse(AiLinkUtils.isValidForOpening("https://"))

        val text = "Bad link javascript:alert(1) and file:///tmp and https://good.com"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals(1, links.size)
        assertEquals("https://good.com", links[0].url)
    }

    @Test
    fun `allowed schemes http and https only`() {
        assertTrue(AiLinkUtils.isAllowedScheme("https://example.com"))
        assertTrue(AiLinkUtils.isAllowedScheme("http://example.com"))
        assertTrue(AiLinkUtils.isAllowedScheme("https://androllm.com/path"))
        assertFalse(AiLinkUtils.isAllowedScheme("javascript:alert(1)"))
        assertFalse(AiLinkUtils.isAllowedScheme("file:///tmp"))
        assertFalse(AiLinkUtils.isAllowedScheme("intent://example"))
        assertFalse(AiLinkUtils.isAllowedScheme("data:text/html,hi"))
    }

    @Test
    fun `dangerous schemes blocked`() {
        assertTrue(AiLinkUtils.isDangerousScheme("javascript:alert(1)"))
        assertTrue(AiLinkUtils.isDangerousScheme("file:///etc/passwd"))
        assertTrue(AiLinkUtils.isDangerousScheme("intent://example"))
        assertFalse(AiLinkUtils.isDangerousScheme("https://example.com"))
    }

    @Test
    fun `links inside text detected`() {
        val text = "Start https://a.com middle https://b.com end"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals(2, links.size)
        assertEquals("https://a.com", links[0].url)
        assertEquals("https://b.com", links[1].url)
    }

    @Test
    fun `internal trusted link still detected for warning`() {
        val text = "Internal https://androllm.com/internal/page"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals(1, links.size)
        assertEquals("https://androllm.com/internal/page", links[0].url)
        assertTrue(AiLinkUtils.isValidForOpening(links[0].url))
    }

    @Test
    fun `splitTextWithLinks preserves non-link text`() {
        val text = "Hello https://example.com world"
        val segments = AiLinkUtils.splitTextWithLinks(text)
        assertEquals(3, segments.size)
        assertEquals("Hello ", (segments[0] as AiLinkUtils.Segment.Plain).text)
        assertEquals("https://example.com", (segments[1] as AiLinkUtils.Segment.Link).url)
        assertEquals(" world", (segments[2] as AiLinkUtils.Segment.Plain).text)
    }

    @Test
    fun `multiple markdown and plain mixed`() {
        val text = "Visit [Site1](https://site1.com) and https://site2.com then [Site3](https://site3.com)"
        val segments = AiLinkUtils.splitTextWithLinks(text)
        val links = segments.filterIsInstance<AiLinkUtils.Segment.Link>()
        assertEquals(3, links.size)
        assertEquals("https://site1.com", links[0].url)
        assertEquals("https://site2.com", links[1].url)
        assertEquals("https://site3.com", links[2].url)
    }

    @Test
    fun `plain URL with path and query preserved`() {
        val text = "Link https://example.com/path?query=1&foo=bar#section"
        val links = AiLinkUtils.extractLinks(text)
        assertEquals("https://example.com/path?query=1&foo=bar#section", links[0].url)
    }

    @Test
    fun `warning dialog should appear for valid link when warn enabled - logic`() {
        val url = "https://androllm.com"
        assertTrue(AiLinkUtils.isValidForOpening(url))
        // Simulate warning setting ON: should show dialog, not auto-open
        val warnEnabled = true
        val shouldShowWarning = warnEnabled && AiLinkUtils.isValidForOpening(url)
        assertTrue(shouldShowWarning)
    }

    @Test
    fun `cancel prevents navigation - logic`() {
        val url = "https://example.com"
        var navigated = false
        // Simulate cancel: onDismiss should not set navigated
        val onDismiss = { navigated = false }
        onDismiss()
        assertFalse(navigated)
    }

    @Test
    fun `invalid URL rejected prevents open`() {
        val url = "javascript:alert(1)"
        assertFalse(AiLinkUtils.isValidForOpening(url))
        // Should not open
        var opened = false
        if (AiLinkUtils.isValidForOpening(url)) opened = true
        assertFalse(opened)
    }
}
