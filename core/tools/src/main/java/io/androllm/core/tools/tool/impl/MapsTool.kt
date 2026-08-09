package io.androllm.core.tools.tool.impl

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opens Google Maps navigation to a destination ("navigate to the airport").
 * Turn-by-turn guidance requires Google Maps to be installed; falls back to a
 * plain geo: query otherwise.
 */
@Singleton
class MapsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_navigation",
        description = "Open turn-by-turn navigation in Google Maps to a destination or address.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("destination") {
                    put("type", "string")
                    put("description", "Destination name, address or 'lat,lon'")
                }
                putJsonObject("mode") {
                    put("type", "string")
                    put("description", "Optional travel mode: drive, walk, transit or bicycle")
                }
            }
            putJsonArray("required") { add("destination") }
        },
        permission = ToolPermission.MAPS,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val destination = ToolArgs.str(arguments, "destination", "query", "place")
            ?: return ToolResult.Failure("Missing required argument: destination")
        val mode = ToolArgs.str(arguments, "mode")

        val navUri = ToolIntents.mapsNavigationUri(destination, mode)
        val launched = ToolIntents.launch(context, Intent(Intent.ACTION_VIEW, navUri))
        if (!launched) {
            // Google Maps missing → fall back to a generic geo: query.
            val fallback = ToolIntents.launch(
                context,
                Intent(Intent.ACTION_VIEW, ToolIntents.geoUri(destination))
            )
            if (!fallback) return ToolResult.Failure("No maps app found to navigate to '$destination'.")
        }
        return ToolResult.Success(
            summary = "Navigation opened to $destination${mode?.let { " ($it)" } ?: ""}.",
            data = buildJsonObject {
                put("destination", destination)
                put("mode", mode ?: "drive")
                put("status", "navigation-opened")
            }
        )
    }
}

/**
 * Searches for places near the user's location and shows them on a map
 * ("find the nearest hospital").
 */
@Singleton
class MapsSearchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "search_places",
        description = "Search nearby places (hospitals, restaurants, ATMs, gas stations...) and show the results on a map.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "What to find, e.g. 'nearest hospital' or 'coffee near me'")
                }
            }
            putJsonArray("required") { add("query") }
        },
        permission = ToolPermission.MAPS,
        category = ToolCategory.PRODUCTIVITY
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = ToolArgs.str(arguments, "query", "place")
            ?: return ToolResult.Failure("Missing required argument: query")
        val launched = ToolIntents.launch(
            context,
            Intent(Intent.ACTION_VIEW, ToolIntents.geoUri(query))
        )
        if (!launched) return ToolResult.Failure("No maps app found to search '$query'.")
        return ToolResult.Success(
            summary = "Showing map results for \"$query\".",
            data = buildJsonObject {
                put("query", query)
                put("status", "search-opened")
            }
        )
    }
}
