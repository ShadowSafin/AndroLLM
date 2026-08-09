package io.androllm.core.tools.tool.impl

import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One structured search result fed back to the LLM. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

/**
 * Pure parsers for the two search providers — no I/O, fully unit-testable.
 *
 * - [parseInstantAnswer]: DuckDuckGo Instant Answer API (great for facts,
 *   often empty for open-ended queries).
 * - [parseDdgHtml]: DuckDuckGo HTML results page (real web results, no key).
 */
object WebSearchParser {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Provider 1: api.duckduckgo.com (Instant Answer JSON) ───────────────

    fun parseInstantAnswer(body: String): List<SearchResult> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<SearchResult>()

        val heading = root["Heading"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val abstractText = root["AbstractText"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val abstractUrl = root["AbstractURL"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (abstractText != null) {
            out += SearchResult(
                title = heading ?: "Result",
                snippet = abstractText.take(500),
                url = abstractUrl ?: ""
            )
        }

        fun collect(topics: JsonArray?) {
            for (el in topics ?: return) {
                val obj = el as? JsonObject ?: continue
                val text = obj["Text"]?.jsonPrimitive?.content
                if (text != null) {
                    out += SearchResult(
                        title = text.substringBefore(" - ").take(120),
                        snippet = text.take(400),
                        url = obj["FirstURL"]?.jsonPrimitive?.content ?: ""
                    )
                } else {
                    obj["Topics"]?.jsonArray?.let { collect(it) }
                }
            }
        }
        collect(root["RelatedTopics"]?.jsonArray)
        return out
    }

    // ── Provider 2: html.duckduckgo.com (full web results) ─────────────────

    fun parseDdgHtml(html: String): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val titleRe = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRe = Regex(
            """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val titles = titleRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { unescape(it.groupValues[1]) }.toList()

        for ((i, m) in titles.withIndex()) {
            val title = unescape(m.groupValues[2]).trim().replace(Regex("""\s+"""), " ")
            val href = m.groupValues[1]
            val url = decodeDdgUrl(href)
            if (title.isBlank() || url.isBlank()) continue
            out += SearchResult(
                title = title.take(160),
                snippet = snippets.getOrNull(i)?.take(320) ?: "",
                url = url
            )
        }
        return out
    }

    // ── Provider 3: www.bing.com/search (real web results, direct URLs) ────

    fun parseBingHtml(html: String): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val blockRe = Regex(
            """<li class="[^"]*b_algo[^"]*"[^>]*>(.*?)</li>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val linkRe = Regex(
            """<h2[^>]*><a href="([^"]+)"[^>]*>(.*?)</a></h2>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRe = Regex(
            """<p class="b_lineclamp[^"]*"[^>]*>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        for (block in blockRe.findAll(html)) {
            val content = block.groupValues[1]
            val link = linkRe.find(content) ?: continue
            val url = link.groupValues[1].trim()
            val title = unescape(link.groupValues[2]).trim().replace(Regex("""\s+"""), " ")
            if (title.isBlank() || url.isBlank() || url.startsWith("javascript:")) continue
            val snippet = snippetRe.find(content)
                ?.let { unescape(it.groupValues[1]) }
                ?.take(320)
                ?: ""
            out += SearchResult(title = title.take(160), snippet = snippet, url = url)
        }
        return out
    }

    /** DDG wraps result URLs as //duckduckgo.com/l/?uddg=<encoded> — unwrap. */
    private fun decodeDdgUrl(href: String): String {
        val u = if (href.startsWith("//")) "https:$href" else href
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(u)?.groupValues?.get(1) ?: return u
        return runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(u)
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace(Regex("""\s{2,}"""), " ")
        // A stripped tag before punctuation leaves "word ." — fix it.
        .replace(Regex("""\s+([.,!?;:)])"""), "$1")
        .trim()
}
