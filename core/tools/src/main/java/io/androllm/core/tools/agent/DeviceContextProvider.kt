package io.androllm.core.tools.agent

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects the current device facts the agent should never need to ask for:
 * time, date, battery, charging state, clipboard, foreground app, device
 * model, Android version, screen size, free storage and network type.
 *
 * Every fact is best-effort and wrapped: a missing permission or an older API
 * simply drops that line from the context block instead of failing the turn.
 * These facts are re-collected at the start of every turn so they are always
 * fresh (never cached).
 */
@Singleton
class DeviceContextProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val timeFormat = SimpleDateFormat("EEE, MMM d, yyyy • h:mm a", Locale.US)

    /** Renders the facts as "key: value" lines, newest-important first. */
    fun collect(): List<String> {
        val facts = mutableListOf<String>()
        runCatching {
            facts += "time: ${timeFormat.format(Date())}"
        }
        batteryFacts()?.let { facts += it }
        clipboardFacts()?.let { facts += "clipboard: $it" }
        foregroundApp()?.let { facts += "current_app: $it" }
        runCatching {
            facts += "device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        }
        runCatching {
            val dm = context.resources.displayMetrics
            facts += "screen: ${dm.widthPixels}x${dm.heightPixels}px, ${dm.densityDpi}dpi"
        }
        storageFreeGb()?.let { facts += "free_storage: $it GB" }
        networkType()?.let { facts += "network: $it" }
        return facts
    }

    private fun batteryFacts(): List<String>? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return null
        val pct = (level * 100 / scale.coerceAtLeast(1)).coerceIn(0, 100)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING
        listOf("battery: $pct%${if (charging) " (charging)" else ""}")
    }.getOrNull()

    private fun clipboardFacts(): String? = runCatching {
        // On API 33+ apps may only read the clipboard while focused; the read
        // below throws SecurityException and is dropped — no prompt is shown.
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            ?.take(80) ?: return null
        text.ifBlank { null }
    }.getOrNull()

    private fun foregroundApp(): String? = runCatching {
        if (Build.VERSION.SDK_INT < 28) return null
        // Needs the "Usage access" special permission — silently skipped when
        // the user hasn't granted it (never prompts).
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            )
        }
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, now - 10 * 60 * 1000L, now
        )
        val top = stats.maxByOrNull { it.lastTimeUsed } ?: return null
        if (top.packageName == context.packageName) return null
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(top.packageName, 0)
            ).toString()
        }.getOrDefault(top.packageName)
        label.take(40)
    }.getOrNull()

    private fun storageFreeGb(): Double? = runCatching {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val free = stat.availableBytes.toDouble()
        val gb = free / (1024.0 * 1024.0 * 1024.0)
        String.format(Locale.US, "%.1f", gb).toDouble()
    }.getOrNull()

    private fun networkType(): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> null
        }
    }.getOrNull()
}
