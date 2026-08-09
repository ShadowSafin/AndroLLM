package io.androllm.core.tools.tool.impl

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mirrors active notifications into an in-memory queue that
 * [NotificationTool] reads. The user must grant notification access in
 * Android settings (Settings → Notifications → Notification access); until
 * then [isEnabled] stays false and the tool reports a clear message.
 */
class AndroNotificationListener : NotificationListenerService() {

    companion object {
        val active = ConcurrentLinkedQueue<ActiveNotification>()
        @Volatile var isEnabled: Boolean = false
    }

    data class ActiveNotification(
        val packageName: String,
        val title: String,
        val text: String,
        val postedAt: Long
    )

    override fun onListenerConnected() {
        isEnabled = true
        refresh()
    }

    override fun onListenerDisconnected() {
        isEnabled = false
        active.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        active.clear()
        runCatching {
            for (sbn in activeNotifications) {
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) continue
                active.offer(
                    ActiveNotification(
                        packageName = sbn.packageName,
                        title = title,
                        text = text,
                        postedAt = sbn.postTime
                    )
                )
            }
        }
    }
}
