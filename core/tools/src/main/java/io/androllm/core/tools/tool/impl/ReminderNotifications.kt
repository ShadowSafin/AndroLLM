package io.androllm.core.tools.tool.impl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notification plumbing for reminders and alarms: two channels plus the
 * broadcast receiver that actually posts the notification when the
 * AlarmManager fires. Declared in the module manifest (merges into the app).
 */
object ReminderNotifications {

    const val REMINDER_CHANNEL = "reminders"
    const val ALARM_CHANNEL = "alarms"
    const val EXTRA_TEXT = "extra_text"
    const val EXTRA_CHANNEL = "extra_channel"
    const val EXTRA_IS_ALARM = "extra_is_alarm"

    private val requestCodes = AtomicInteger(1000)

    fun nextRequestCode(): Int = requestCodes.incrementAndGet()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(REMINDER_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    REMINDER_CHANNEL,
                    "Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Scheduled reminder notifications" }
            )
        }
        if (nm.getNotificationChannel(ALARM_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ALARM_CHANNEL,
                    "Alarms",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alarm notifications"
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
                }
            )
        }
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ensureChannels(context)
            val text = intent.getStringExtra(EXTRA_TEXT) ?: "Reminder"
            val isAlarm = intent.getBooleanExtra(EXTRA_IS_ALARM, false)
            val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: REMINDER_CHANNEL
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (isAlarm) "⏰ Alarm" else "Reminder")
                .setContentText(text)
                .setPriority(if (isAlarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(nextRequestCode(), notification)
        }
    }
}
