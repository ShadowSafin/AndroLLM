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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * GitHub REST API access (no auth needed for public data — the anonymous
 * rate limit is 60 req/hr, plenty for a search + a release check). Uses the
 * same shared Ktor [HttpClient] as [WebSearchTool].
 */
@Singleton
class GitHubTool @Inject constructor(
    private val httpClient: HttpClient
) : Tool {

    private val json = Json { ignoreUnknownKeys = true }

    override val spec = ToolSpec(
        name = "github",
        description = "Query GitHub: 'search_repos <query>' finds repositories; 'latest_release <owner>/<repo>' returns the newest release. Example: github(search='search_repos', repo='BerriAI/litellm')",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("search_repos", "latest_release", "repo_info").forEach { add(it) } }
                }
                putJsonObject("query") { put("type", "string"); put("description", "Search query for search_repos" ) }
                putJsonObject("repo") { put("type", "string"); put("description", "owner/repo for latest_release and repo_info" ) }
            }
            putJsonArray("required") { add("action") }
        },
        permission = ToolPermission.GITHUB,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = ToolArgs.str(arguments, "action", "type")
            ?: return ToolResult.Failure("Missing required argument: action")
        return when (action.lowercase()) {
            "search_repos", "search" -> searchRepos(ToolArgs.str(arguments, "query", "q"))
            "latest_release", "release" -> latestRelease(ToolArgs.str(arguments, "repo"))
            "repo_info", "repo" -> repoInfo(ToolArgs.str(arguments, "repo"))
            else -> ToolResult.Failure("Unknown github action '$action'. Use search_repos, latest_release or repo_info.")
        }
    }

    private suspend fun searchRepos(query: String?): ToolResult {
        if (query.isNullOrBlank()) return ToolResult.Failure("Missing required argument: query")
        val body = get("https://api.github.com/search/repositories?q=${urlEncode(query)}&sort=stars&per_page=5")
            ?: return ToolResult.Failure("GitHub search failed — check your connection.")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("GitHub returned an unexpected response.")
        val items = root["items"] as? JsonArray ?: return ToolResult.Failure("No repositories found for '$query'.")
        val repos = items.mapNotNull { it as? JsonObject }.map { item ->
            Repo(
                fullName = item["full_name"]?.jsonPrimitive?.contentOrNull ?: "",
                description = item["description"]?.jsonPrimitive?.contentOrNull ?: "",
                stars = item["stargazers_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                url = item["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
                language = item["language"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
        val data = buildJsonObject {
            put("query", query)
            putJsonArray("repos") {
                repos.forEach { r ->
                    add(buildJsonObject {
                        put("full_name", r.fullName)
                        put("stars", r.stars)
                        put("language", r.language)
                        put("description", r.description.take(160))
                        put("url", r.url)
                    })
                }
            }
        }
        return ToolResult.Success(
            repos.joinToString("; ") { "${it.fullName} (⭐${it.stars})" }.ifBlank { "No repositories found." },
            data
        )
    }

    private suspend fun latestRelease(repo: String?): ToolResult {
        if (repo.isNullOrBlank() || !repo.contains('/')) {
            return ToolResult.Failure("Missing required argument: repo (owner/name, e.g. BerriAI/litellm)")
        }
        val body = get("https://api.github.com/repos/$repo/releases/latest")
            ?: return ToolResult.Failure("GitHub request failed — check the repo name and your connection.")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("Repo '$repo' has no releases or was not found.")
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val name = root["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val published = root["published_at"]?.jsonPrimitive?.contentOrNull ?: ""
        val notes = root["body"]?.jsonPrimitive?.contentOrNull?.take(600) ?: ""
        val data = buildJsonObject {
            put("repo", repo)
            put("tag", tag)
            put("name", name)
            put("published_at", published)
            put("notes", notes.take(600))
        }
        return ToolResult.Success("$repo latest release: $tag${if (name.isNotBlank()) " — $name" else ""} (published $published).\n$notes", data)
    }

    private suspend fun repoInfo(repo: String?): ToolResult {
        if (repo.isNullOrBlank() || !repo.contains('/')) {
            return ToolResult.Failure("Missing required argument: repo (owner/name)")
        }
        val body = get("https://api.github.com/repos/$repo")
            ?: return ToolResult.Failure("GitHub request failed — check the repo name and your connection.")
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("Repo '$repo' was not found.")
        val fullName = root["full_name"]?.jsonPrimitive?.contentOrNull ?: repo
        val stars = root["stargazers_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
        val forks = root["forks_count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
        val desc = root["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val lang = root["language"]?.jsonPrimitive?.contentOrNull ?: ""
        return ToolResult.Success(
            "$fullName — ⭐$stars · 🍴$forks · $lang\n$desc",
            buildJsonObject {
                put("full_name", fullName)
                put("stars", stars)
                put("forks", forks)
                put("language", lang)
                put("description", desc.take(200))
            }
        )
    }

    private suspend fun get(url: String): String? = runCatching {
        val resp = httpClient.get(url) {
            header(HttpHeaders.UserAgent, "AndroLLM")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        if (resp.status.value == 403) {
            // Rate limited — surface a clear message instead of a generic parse error.
            return null
        }
        resp.bodyAsText()
    }.getOrNull()

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private data class Repo(
        val fullName: String,
        val description: String,
        val stars: Long,
        val url: String,
        val language: String
    )
}
