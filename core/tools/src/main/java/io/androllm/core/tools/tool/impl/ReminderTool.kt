package io.androllm.core.tools.tool.impl

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.utils.NotificationPermissions
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
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
 * Schedules a one-shot reminder that fires a notification at the requested
 * time ("remind me to call the dentist tomorrow at 10"). Uses the system
 * AlarmManager; exact timing falls back to inexact when the OS blocks it.
 */
@Singleton
class ReminderTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "create_reminder",
        description = "Schedule a reminder that notifies at a specific time. Provide the text and when ('in 20 minutes', 'tomorrow 09:00', an ISO time or epoch millis).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("when") {
                    put("type", "string")
                    put("description", "When to fire, e.g. 'in 20 minutes', 'tomorrow 09:00' or an ISO time")
                }
            }
            putJsonArray("required") { add("text"); add("when") }
        },
        permission = ToolPermission.ALARMS,
        category = ToolCategory.PRODUCTIVITY
    )

    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text", "message", "reminder")
            ?: return ToolResult.Failure("Missing required argument: text")
        val whenRaw = ToolArgs.str(arguments, "when", "time", "at")
        val millis = whenRaw?.let { ToolTime.parseMillis(it) } ?: parseRelative(whenRaw)

        if (millis == null || millis <= System.currentTimeMillis()) {
            return ToolResult.Failure("Reminder time '$whenRaw' is missing or in the past.")
        }
        if (!NotificationPermissions.canNotify(context)) {
            return ToolResult.Failure("Notifications are disabled for AndroLLM — enable them in Android settings.")
        }
        return runCatching {
            ReminderNotifications.ensureChannels(context)
            val intent = Intent(context, ReminderNotifications.ReminderReceiver::class.java)
                .putExtra(ReminderNotifications.EXTRA_TEXT, text)
                .putExtra(ReminderNotifications.EXTRA_CHANNEL, ReminderNotifications.REMINDER_CHANNEL)
            val pi = PendingIntent.getBroadcast(
                context,
                ReminderNotifications.nextRequestCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } catch (e: SecurityException) {
                // Exact alarms blocked on API 31+ without the permission.
                am.set(AlarmManager.RTC_WAKEUP, millis, pi)
            }
            ToolResult.Success(
                summary = "Reminder set for ${fmt.format(Date(millis))}: \"$text\".",
                data = buildJsonObject {
                    put("text", text)
                    put("when", millis)
                    put("status", "scheduled")
                }
            )
        }.getOrElse {
            ToolResult.Failure("Could not schedule the reminder: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** Handles natural phrases: "in 20 minutes", "in 2 hours", "in 30 seconds". */
    private fun parseRelative(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val t = raw.lowercase().trim()
        if (!t.contains("in ")) return null
        val m = Regex("""in\s+(\d+)\s*(second|minute|min|hour|hr|day)s?""").find(t) ?: return null
        val amount = m.groupValues[1].toLongOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "second" -> 1000L
            "minute", "min" -> 60_000L
            "hour", "hr" -> 3_600_000L
            "day" -> 86_400_000L
            else -> 60_000L
        }
        return System.currentTimeMillis() + amount * mult
    }
}

/**
 * Sets an alarm at a wall-clock time ("set an alarm for 7 AM"). Same
 * mechanism as [ReminderTool], with its own channel and label.
 */
@Singleton
class AlarmTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Set an alarm that rings at a clock time. Provide the time ('07:00', '7 AM', ISO time or epoch millis) and an optional label.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("time") {
                    put("type", "string")
                    put("description", "Alarm time, e.g. '07:00' or '7 AM'")
                }
                putJsonObject("label") { put("type", "string") }
            }
            putJsonArray("required") { add("time") }
        },
        permission = ToolPermission.ALARMS,
        category = ToolCategory.PRODUCTIVITY
    )

    private val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val timeRaw = ToolArgs.str(arguments, "time", "when", "at")
            ?: return ToolResult.Failure("Missing required argument: time")
        val label = ToolArgs.str(arguments, "label", "name").orEmpty().ifBlank { "Alarm" }
        val millis = ToolTime.parseMillis(timeRaw)
            ?: return ToolResult.Failure("Could not understand alarm time '$timeRaw'.")
        if (millis <= System.currentTimeMillis()) {
            return ToolResult.Failure("That alarm time is in the past.")
        }
        if (!NotificationPermissions.canNotify(context)) {
            return ToolResult.Failure("Notifications are disabled for AndroLLM — enable them in Android settings.")
        }
        return runCatching {
            ReminderNotifications.ensureChannels(context)
            val intent = Intent(context, ReminderNotifications.ReminderReceiver::class.java)
                .putExtra(ReminderNotifications.EXTRA_TEXT, label)
                .putExtra(ReminderNotifications.EXTRA_CHANNEL, ReminderNotifications.ALARM_CHANNEL)
                .putExtra(ReminderNotifications.EXTRA_IS_ALARM, true)
            val pi = PendingIntent.getBroadcast(
                context,
                ReminderNotifications.nextRequestCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, millis, pi)
            }
            ToolResult.Success(
                summary = "Alarm set for ${fmt.format(Date(millis))} ($label).",
                data = buildJsonObject {
                    put("label", label)
                    put("when", millis)
                    put("status", "scheduled")
                }
            )
        }.getOrElse {
            ToolResult.Failure("Could not set the alarm: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
