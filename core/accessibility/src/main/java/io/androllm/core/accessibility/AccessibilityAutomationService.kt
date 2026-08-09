package io.androllm.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The accessibility service behind the UI Automation engine.
 *
 * The user enables it once in Settings → Accessibility → AndroLLM UI
 * Automation. It is deliberately quiet: it only wakes to feed the window
 * monitor / UI state tracker (cheap state updates, no polling), and the
 * executor performs gestures only when a task asks for them.
 */
@AndroidEntryPoint
class AccessibilityAutomationService : AccessibilityService() {

    @Inject lateinit var controller: AccessibilityController
    @Inject lateinit var settingsStore: AccessibilitySettingsStore
    @Inject lateinit var debug: AccessibilityDebugStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller.bind(this)
        scope.launch {
            if (settingsStore.current().showStatusNotification) {
                runCatching { startMonitoringNotification() }
            }
        }
        Timber.i("AccessibilityAutomationService: connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // A throwing handler must never kill the service binder thread.
        runCatching { controller.onAccessibilityEvent(event) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        controller.unbind()
        stopForegroundCompat()
        Timber.i("AccessibilityAutomationService: unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        controller.unbind()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitoringNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.accessibility_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(getString(R.string.accessibility_notification_title))
            .setContentText(getString(R.string.accessibility_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    companion object {
        private const val NOTIFICATION_ID = 9001
        private const val NOTIFICATION_CHANNEL = "accessibility_automation"

        /** True when the user has enabled the service in system settings. */
        fun isServiceEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${
                AccessibilityAutomationService::class.java.name
            }"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            // Exact component match only — a loose package-name check would also
            // match other enabled services from this app (e.g. the notification
            // listener), falsely reporting the accessibility service as on.
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /** Opens the system accessibility settings where the service lives. */
        fun openSettings(context: Context): Boolean = runCatching {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
