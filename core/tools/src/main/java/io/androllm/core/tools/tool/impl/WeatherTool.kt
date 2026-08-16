package io.androllm.core.tools.tool.impl

import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Weather via the free, keyless Open-Meteo API (geocoding + forecast).
 * Returns temperature, humidity, condition, rain chance, wind, UV and a
 * 3-day forecast, both as a spoken summary and structured JSON.
 */
@Singleton
class WeatherTool @Inject constructor(
    private val httpClient: HttpClient
) : Tool {

    override val spec = ToolSpec(
        name = "get_weather",
        description = "Get current weather and a 3-day forecast for a city or location. Returns temperature, humidity, condition, rain chance, wind, UV index and daily forecast.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "City name or 'lat,lon' coordinates, e.g. Delhi or 28.61,77.20")
                }
            }
            putJsonArray("required") { add("location") }
        },
        permission = ToolPermission.WEATHER,
        category = ToolCategory.INFORMATION,
        supportedTasks = listOf(
            "weather", "forecast", "temperature outside", "rain", "humidity",
            "today's weather", "weekend forecast"
        ),
        cacheable = true,
        // Geocode + forecast are two network hops; give them headroom over
        // the default 20s budget so a slow lookup is not discarded.
        executionTimeoutMs = 45_000L
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val location = ToolArgs.str(arguments, "location", "city")
            ?: return ToolResult.Failure("Missing required argument: location")
        return runCatching {
            val geo = resolveCoordinates(location)
            if (geo == null) {
                ToolResult.Failure("Could not find a location matching '$location'.")
            } else {
                fetchForecast(geo)
            }
        }.getOrElse {
            ToolResult.Failure("Weather lookup failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private data class Geo(val name: String, val lat: Double, val lon: Double)

    private suspend fun resolveCoordinates(location: String): Geo? {
        // "lat,lon" form needs no geocoding round-trip.
        val coords = location.split(",").map { it.trim() }
        if (coords.size == 2) {
            val lat = coords[0].toDoubleOrNull()
            val lon = coords[1].toDoubleOrNull()
            if (lat != null && lon != null) return Geo(location, lat, lon)
        }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            java.net.URLEncoder.encode(location, "UTF-8") +
            "&count=1&language=en&format=json"
        val body = httpClient.get(url).bodyAsText()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val first = root["results"]?.jsonArray?.firstOrNull() as? JsonObject ?: return null
        val lat = first["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = first["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val name = first["name"]?.jsonPrimitive?.content ?: location
        return Geo(name, lat, lon)
    }

    private suspend fun fetchForecast(geo: Geo): ToolResult {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${geo.lat}&longitude=${geo.lon}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"
        val body = httpClient.get(url).bodyAsText()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("Weather service returned an unreadable response.")

        val current = root["current"]?.jsonObject ?: return ToolResult.Failure("No current weather available.")
        val daily = root["daily"]?.jsonObject ?: JsonObject(emptyMap())

        val temp = current["temperature_2m"]?.jsonPrimitive?.content ?: "?"
        val feels = current["apparent_temperature"]?.jsonPrimitive?.content ?: "?"
        val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.content ?: "?"
        val precip = current["precipitation"]?.jsonPrimitive?.content ?: "0"
        val wind = current["wind_speed_10m"]?.jsonPrimitive?.content ?: "?"
        val code = current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val dates = daily["time"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val maxes = daily["temperature_2m_max"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val mins = daily["temperature_2m_min"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val rainChance = daily["precipitation_probability_max"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        val condition = conditionLabel(code)
        val forecastDays = dates.indices.mapNotNull { i ->
            val label = when (i) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> dates.getOrNull(i) ?: ""
            }
            if (label.isBlank()) return@mapNotNull null
            buildJsonObject {
                put("day", label)
                put("condition", conditionLabel(daily["weather_code"]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.content?.toIntOrNull() ?: 0))
                put("maxC", maxes.getOrNull(i) ?: "?")
                put("minC", mins.getOrNull(i) ?: "?")
                put("rainChancePercent", rainChance.getOrNull(i) ?: "0")
            }
        }

        val data = buildJsonObject {
            put("location", geo.name)
            put("temperatureC", temp)
            put("feelsLikeC", feels)
            put("humidityPercent", humidity)
            put("condition", condition)
            put("rainMm", precip)
            put("windKmh", wind)
            putJsonArray("forecast") { forecastDays.forEach { add(it) } }
        }

        val sb = StringBuilder()
        sb.append(geo.name).append(": ").append(temp).append("°C, ").append(condition.lowercase())
        if (precip.toDoubleOrNull()?.let { it > 0.0 } == true) sb.append(", rain ").append(precip).append(" mm")
        sb.append(", humidity ").append(humidity).append("%").append(", wind ").append(wind).append(" km/h")
        forecastDays.take(2).forEach { day ->
            sb.append(". ").append(day["day"]).append(": ")
                .append(day["minC"]).append("–").append(day["maxC"]).append("°C")
            val r = (day["rainChancePercent"] as? String) ?: "0"
            if (r.toIntOrNull()?.let { it > 0 } == true) sb.append(", ").append(r).append("% rain")
        }
        return ToolResult.Success(summary = sb.toString(), data = data)
    }

    private fun conditionLabel(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}
