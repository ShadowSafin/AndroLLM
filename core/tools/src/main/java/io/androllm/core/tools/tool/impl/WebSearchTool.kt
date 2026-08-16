package io.androllm.core.tools.tool.impl

import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber

/**
 * Web search with a provider fallback chain:
 *
 * 1. DuckDuckGo Instant Answer API — structured, great for facts, but empty
 *    for most open-ended queries ("Android 17", "NVIDIA news").
 * 2. DuckDuckGo HTML results — real web results, no API key, parsed into
 *    {title, snippet, url}.
 *
 * Never returns a blank answer: with zero results it still returns a clear
 * message (plus structured `results: []`) so the LLM has something to say.
 * Every request logs provider, query, status and parse counts (STEP 4).
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val httpClient: HttpClient
) : Tool {

    override val spec = ToolSpec(
        name = "search_web",
        description = "Search the web for a query and return the top results with titles, snippets and URLs. Use when asked to look something up, check news, prices or facts.",
        supportedTasks = listOf(
            "search", "web search", "look up", "news", "current events",
            "prices", "facts", "latest", "google"
        ),
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "The search query")
                }
            }
            putJsonArray("required") { add("query") }
        },
        permission = ToolPermission.SEARCH,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = ToolArgs.str(arguments, "query", "q")
            ?: return ToolResult.Failure("Missing required argument: query")
        val startedAt = System.currentTimeMillis()

        // Provider 1 — Instant Answer (facts/definitions).
        var results = runCatching {
            val body = fetch("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&t=androllm&q=${query.encoded()}")
            WebSearchParser.parseInstantAnswer(body)
        }.onFailure { t ->
            Timber.w(t, "WebSearchTool: instant-answer provider failed — trying HTML provider")
        }.getOrDefault(emptyList())

        // Provider 2 — DDG HTML web results (real web results, no key).
        var provider = "instant-answer"
        if (results.isEmpty()) {
            results = runCatching {
                val body = fetch("https://html.duckduckgo.com/html/?q=${query.encoded()}")
                WebSearchParser.parseDdgHtml(body)
            }.onFailure { t ->
                Timber.w(t, "WebSearchTool: duckduckgo-html provider failed — trying Bing")
            }.getOrDefault(emptyList())
            provider = "duckduckgo-html"
        }

        // Provider 3 — Bing HTML: DDG frequently blocks mobile/datacenter IPs
        // with an anomaly page; Bing is a second, independent no-key source.
        if (results.isEmpty()) {
            results = runCatching {
                val body = fetch("https://www.bing.com/search?q=${query.encoded()}&count=10")
                WebSearchParser.parseBingHtml(body)
            }.onFailure { t ->
                Timber.e(t, "WebSearchTool: bing provider failed")
            }.getOrDefault(emptyList())
            provider = "bing"
        }

        val durationMs = System.currentTimeMillis() - startedAt
        Timber.i(
            "WebSearchTool: provider=%s query='%s' results=%d in %dms",
            provider, query, results.size, durationMs
        )

        if (results.isEmpty()) {
            // Never blank — and never a bogus failure: the search RAN, it just
            // found nothing. A Success with empty structured data lets the LLM
            // honestly say "I couldn't find results for X" instead of treating
            // it as an error.
            return ToolResult.Success(
                summary = "No web results found for \"$query\" — check the spelling or try a different query.",
                data = buildJsonObject {
                    put("query", query)
                    put("provider", provider)
                    put("results", 0)
                    putJsonArray("items") { }
                }
            )
        }

        val top = results.take(6)
        val data = buildJsonObject {
            put("query", query)
            put("provider", provider)
            put("results", top.size)
            putJsonArray("items") {
                top.forEach { r ->
                    add(
                        buildJsonObject {
                            put("title", r.title)
                            put("snippet", r.snippet)
                            put("url", r.url)
                        }
                    )
                }
            }
        }
        val sb = StringBuilder("Search results for \"$query\":")
        top.take(5).forEachIndexed { i, r ->
            sb.append(' ').append(i + 1).append(". ")
                .append(r.title).append(" — ")
                .append(r.snippet.take(160))
        }
        return ToolResult.Success(summary = sb.toString(), data = data)
    }

    private suspend fun fetch(url: String): String {
        val resp = httpClient.get(url) {
            // A browser-ish UA keeps the HTML endpoint from blocking bots.
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36")
        }
        val body = resp.bodyAsText()
        Timber.i("WebSearchTool: GET %s → HTTP %s (%d bytes)", url.substringBefore('?'), resp.status.value, body.length)
        return body
    }

    private fun String.encoded(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
