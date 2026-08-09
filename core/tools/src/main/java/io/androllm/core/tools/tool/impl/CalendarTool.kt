package io.androllm.core.tools.tool.impl

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.utils.PermissionUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Creates and reads calendar events through the system CalendarContract.
 * Requires the standard READ/WRITE_CALENDAR runtime permissions.
 */
@Singleton
class CalendarTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "calendar",
        description = "Create a calendar event or list upcoming events. For create, provide title and a start time (e.g. 'tomorrow 15:00' or an ISO time).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "create or list")
                }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "Start time — 'tomorrow 15:00', '2026-08-09T14:30' or epoch millis")
                }
                putJsonObject("end") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("location") { put("type", "string") }
            }
            putJsonArray("required") { add("action") }
        },
        permission = ToolPermission.CALENDAR,
        category = ToolCategory.PRODUCTIVITY
    )

    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = ToolArgs.str(arguments, "action")?.lowercase() ?: "create"
        return when (action) {
            "create", "add" -> create(arguments)
            "list", "read", "upcoming" -> list()
            else -> ToolResult.Failure("Unknown calendar action '$action' (use create or list).")
        }
    }

    private fun create(arguments: JsonObject): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult.Failure(
                "Calendar permission is not granted. Enable \"Calendar\" for AndroLLM in Android settings, then try again."
            )
        }
        val title = ToolArgs.str(arguments, "title", "summary")
            ?: return ToolResult.Failure("Missing required argument: title")
        val startRaw = ToolArgs.str(arguments, "start", "when", "time")
            ?: return ToolResult.Failure("Missing required argument: start")
        val start = ToolTime.parseMillis(startRaw)
            ?: return ToolResult.Failure("Could not understand start time '$startRaw'.")
        val end = ToolArgs.str(arguments, "end")?.let { ToolTime.parseMillis(it) } ?: (start + 60 * 60 * 1000)
        val description = ToolArgs.str(arguments, "description", "details").orEmpty()
        val location = ToolArgs.str(arguments, "location", "place").orEmpty()

        val calId = defaultCalendarId()
            ?: return ToolResult.Failure("No writable calendar was found on this device.")

        return runCatching {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                if (description.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, description)
                if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                ToolResult.Failure("The calendar refused to create the event.")
            } else {
                ToolResult.Success(
                    summary = "Calendar event created: \"$title\" on ${fmt.format(Date(start))}.",
                    data = buildJsonObject {
                        put("title", title)
                        put("start", start)
                        put("uri", uri.toString())
                        put("status", "created")
                    }
                )
            }
        }.getOrElse {
            ToolResult.Failure("Could not create the calendar event: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun list(): ToolResult {
        if (!hasCalendarPermission()) {
            return ToolResult.Failure("Calendar permission is not granted — enable it in Android settings.")
        }
        return runCatching {
            val now = System.currentTimeMillis()
            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            )
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(now.toString()),
                "${CalendarContract.Events.DTSTART} ASC LIMIT 5"
            ) ?: return ToolResult.Failure("No calendar data available.")
            cursor.use { c ->
                if (c.count == 0) return ToolResult.Success("No upcoming calendar events.", buildJsonObject { put("events", "[]") })
                val events = mutableListOf<JsonObject>()
                val sb = StringBuilder("Upcoming events:")
                while (c.moveToNext() && events.size < 5) {
                    val title = c.getString(0) ?: "(untitled)"
                    val start = c.getLong(1)
                    val loc = c.getString(3) ?: ""
                    events += buildJsonObject {
                        put("title", title)
                        put("start", start)
                        if (loc.isNotBlank()) put("location", loc)
                    }
                    sb.append(' ').append(fmt.format(Date(start))).append(": ").append(title)
                }
                val data = buildJsonObject { putJsonArray("events") { events.forEach { add(it) } } }
                ToolResult.Success(summary = sb.toString(), data = data)
            }
        }.getOrElse {
            ToolResult.Failure("Could not read the calendar: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun hasCalendarPermission(): Boolean =
        PermissionUtils.hasPermission(context, Manifest.permission.READ_CALENDAR) &&
            PermissionUtils.hasPermission(context, Manifest.permission.WRITE_CALENDAR)

    private fun defaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        ) ?: return null
        cursor.use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val level = c.getInt(1)
                if (level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    best = id
                    if (level >= CalendarContract.Calendars.CAL_ACCESS_OWNER) break
                }
            }
            return best
        }
    }
}
