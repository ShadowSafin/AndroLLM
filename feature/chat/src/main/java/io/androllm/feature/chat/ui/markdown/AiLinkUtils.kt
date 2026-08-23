package io.androllm.feature.chat.ui.markdown

import android.net.Uri

/**
 * Utility for detecting and validating AI-generated links in assistant responses.
 * Supports plain URLs, markdown links, links inside text, and multiple links.
 * Ensures trailing punctuation is stripped and only safe schemes are allowed.
 */
object AiLinkUtils {

    private val allowedSchemes = setOf("http", "https")
    private val dangerousSchemes = setOf("javascript", "file", "intent", "data", "vbscript", "about", "blob", "ftp")

    /**
     * Represents a detected link. For markdown links, [displayText] is the text inside `[]`,
     * [url] is the destination. For plain URLs, displayText is null and url is the visible text.
     * [trailingPunctuation] holds any punctuation stripped from the end (e.g. "." after URL)
     * so the renderer can re-append it as plain text.
     */
    data class AiLink(
        val url: String,
        val displayText: String? = null,
        val rangeStart: Int,
        val rangeEnd: Int,
        val trailingPunctuation: String = ""
    )

    /**
     * Validates scheme before opening. Allow only http/https, block dangerous schemes.
     * Uses java.net.URI for JVM unit tests (android.net.Uri returns default values with isReturnDefaultValues).
     */
    fun isAllowedScheme(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        // Try java.net.URI first (works in JVM tests)
        try {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return false
            return scheme in allowedSchemes
        } catch (_: Exception) {
            // Fallback to android.net.Uri for Android runtime
            return try {
                val scheme = Uri.parse(trimmed).scheme?.lowercase() ?: return false
                scheme in allowedSchemes
            } catch (_: Exception) {
                false
            }
        }
    }

    fun isDangerousScheme(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        try {
            val uri = java.net.URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return false
            return scheme in dangerousSchemes
        } catch (_: Exception) {
            return try {
                val scheme = Uri.parse(trimmed).scheme?.lowercase() ?: return false
                scheme in dangerousSchemes
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Full validation before opening: non-blank, allowed scheme, has host/authority, not malformed.
     */
    fun isValidForOpening(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        if (isDangerousScheme(trimmed)) return false
        if (!isAllowedScheme(trimmed)) return false
        // Try java.net.URI first (works in JVM tests)
        try {
            val uri = java.net.URI(trimmed)
            val host = uri.host
            val authority = uri.authority
            if (!host.isNullOrBlank() || !authority.isNullOrBlank()) return true
            // java.net.URI may return null host for some URLs like https://example.com/path
            // Fallback to android.net.Uri
        } catch (_: Exception) {
            // ignore, try android.net.Uri next
        }
        return try {
            val uri = Uri.parse(trimmed)
            val host = uri.host
            val authority = uri.authority
            !host.isNullOrBlank() || !authority.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Strips trailing punctuation from a raw URL match. Returns Pair(cleanedUrl, trailing).
     * E.g. "https://example.com." -> ("https://example.com", ".")
     *      "https://example.com," -> ("https://example.com", ",")
     *      "https://example.com)" -> ("https://example.com", ")")
     */
    fun stripTrailingPunctuation(raw: String): Pair<String, String> {
        if (raw.isEmpty()) return raw to ""
        var end = raw.length
        // Characters to consider stripping when at the very end. Note: we keep "/" "?" "&" "=" "#" etc. as they can be meaningful.
        // We strip . , ! ? : ; ' " ) ] } >  * also . inside but trailing.
        // Special handling for ")" and "]": only strip if not balanced with "(" / "[" inside URL.
        while (end > 0) {
            val c = raw[end - 1]
            when (c) {
                '.', ',', '!', '?', ':', ';' -> end--
                '\'', '"', '`' -> end--
                ')', ']', '}', '>' -> {
                    // Check if there's a matching opening bracket inside the URL before end.
                    // If the URL contains a matching opening bracket, don't strip the closing one (it's part of URL)
                    // Simple heuristic: count opens vs closes in the remaining prefix.
                    val prefix = raw.substring(0, end - 1)
                    val openCount = when (c) {
                        ')' -> prefix.count { it == '(' }
                        ']' -> prefix.count { it == '[' }
                        '}' -> prefix.count { it == '{' }
                        '>' -> prefix.count { it == '<' }
                        else -> 0
                    }
                    val closeCount = prefix.count { it == c } + 1 // include this one
                    // If there are more closes than opens, it's trailing punctuation. Else keep.
                    if (closeCount > openCount) {
                        end--
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        if (end == raw.length) return raw to ""
        return raw.substring(0, end) to raw.substring(end)
    }

    /**
     * Detects all links (markdown and plain) in [text] in order, stripping trailing punctuation.
     * Does not make non-URLs clickable, and handles multiple links.
     */
    fun extractLinks(text: String): List<AiLink> {
        if (text.isBlank()) return emptyList()
        val links = mutableListOf<AiLink>()
        // Markdown links first: [text](url)
        val markdownRegex = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
        for (match in markdownRegex.findAll(text)) {
            val display = match.groupValues[1]
            val rawUrl = match.groupValues[2].trim()
            val (clean, trailing) = stripTrailingPunctuation(rawUrl)
            if (clean.isBlank()) continue
            if (!isAllowedScheme(clean)) {
                // Even if not allowed, we still skip making it clickable, but we don't add it.
                // However spec says prevent malicious URLs from being opened; we simply don't create AiLink for invalid.
                continue
            }
            // Only consider valid URLs
            if (!isValidForOpening(clean)) continue
            links.add(
                AiLink(
                    url = clean,
                    displayText = display,
                    rangeStart = match.range.first,
                    rangeEnd = match.range.last + 1,
                    trailingPunctuation = trailing
                )
            )
        }

        // To avoid double-counting, create a set of ranges already covered by markdown
        fun isInsideMarkdown(pos: Int): Boolean {
            return links.any { pos in it.rangeStart until it.rangeEnd }
        }

        // Plain URLs: http:// or https:// — match any non-whitespace run starting with scheme
        val plainRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        for (match in plainRegex.findAll(text)) {
            val raw = match.value
            val start = match.range.first
            val end = match.range.last + 1
            // Skip if this plain URL is inside an already-detected markdown link
            if (isInsideMarkdown(start)) continue
            val (clean, trailing) = stripTrailingPunctuation(raw)
            if (clean.isBlank()) continue
            if (!isAllowedScheme(clean)) continue
            if (!isValidForOpening(clean)) continue
            // Adjust end to cleaned length (strip trailing punctuation not part of link)
            val cleanEnd = start + clean.length
            links.add(
                AiLink(
                    url = clean,
                    displayText = null,
                    rangeStart = start,
                    rangeEnd = cleanEnd,
                    trailingPunctuation = trailing
                )
            )
        }

        return links.sortedBy { it.rangeStart }
    }

    /**
     * Convenience: returns true if text contains at least one valid AI link.
     */
    fun containsLink(text: String): Boolean = extractLinks(text).isNotEmpty()

    /**
     * Builds an annotated string-friendly representation: splits text into segments of
     * plain text and links, ensuring trailing punctuation is re-appended as plain.
     * Used by the renderer to create clickable spans.
     */
    fun splitTextWithLinks(text: String): List<Segment> {
        val links = extractLinks(text)
        if (links.isEmpty()) return listOf(Segment.Plain(text))
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (link in links) {
            if (link.rangeStart > cursor) {
                segments.add(Segment.Plain(text.substring(cursor, link.rangeStart)))
            }
            if (link.displayText != null) {
                // Markdown: display is link text, url is destination
                segments.add(Segment.Link(displayText = link.displayText, url = link.url, isMarkdown = true))
                // If there was trailing punctuation inside the markdown URL that we stripped, it should be appended as plain after the link
                // But markdown trailing punctuation is rare; we already handle via trailing field if needed.
                // For markdown, the trailing punctuation after the whole "[text](url)" is not inside link; it will be covered by next plain segment.
                // However if we stripped punctuation from url itself (e.g. https://example.com.), we need to re-add trailing after link?
                // For markdown, trailing inside URL is not visible to user anyway, so we ignore.
            } else {
                segments.add(Segment.Link(displayText = link.url, url = link.url, isMarkdown = false))
                if (link.trailingPunctuation.isNotEmpty()) {
                    segments.add(Segment.Plain(link.trailingPunctuation))
                }
            }
            // For markdown, cursor moves to rangeEnd (past ")"), which already excludes any trailing punctuation after ")"
            // For plain, cursor moves to rangeEnd (cleanEnd), but we already emitted trailing as separate segment, so cursor should jump to original end (including trailing)
            // For plain, original match end includes trailing, so we need to advance cursor to start+rawLength, not cleanEnd.
            // To handle that, we look up raw length: for plain links, original raw length = clean + trailing
            val originalEnd = if (link.displayText == null) {
                link.rangeStart + link.url.length + link.trailingPunctuation.length
            } else {
                link.rangeEnd
            }
            cursor = originalEnd
        }
        if (cursor < text.length) {
            segments.add(Segment.Plain(text.substring(cursor)))
        }
        return segments
    }

    sealed interface Segment {
        data class Plain(val text: String) : Segment
        data class Link(val displayText: String, val url: String, val isMarkdown: Boolean = false) : Segment
    }
}
