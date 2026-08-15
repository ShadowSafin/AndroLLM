package io.androllm.core.tools.tool.impl

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Reads the battery state: level, charging status, technology, temperature.
 * No permission needed (sticky broadcast).
 */
@Singleton
class BatteryTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_battery",
        description = "Read the current battery level (%), charging status, health and temperature.",
        permission = ToolPermission.DEVICE,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return ToolResult.Failure("Could not read the battery state.")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return ToolResult.Failure("Could not read the battery state.")
        val pct = (level * 100 / scale.coerceAtLeast(1)).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "battery"
        }
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
        val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        val data = buildJsonObject {
            put("level", pct)
            put("charging", charging)
            put("plugged", source)
            put("temperature_c", tempC)
            put("technology", tech ?: "unknown")
        }
        val summary = "Battery at $pct%" +
            if (charging) " (charging via $source)" else " (not charging)" +
            if (tempC > 0) ", ${"%.1f".format(tempC)}°C" else ""
        return ToolResult.Success(summary, data)
    }
}

/**
 * Sets the media / ring / alarm volume (0..100). Uses the official
 * AudioManager API.
 */
@Singleton
class VolumeTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_volume",
        description = "Set the media volume to a percentage (0–100). Also accepts a stream: media, ring or alarm.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("percent") { put("type", "integer"); put("description", "0–100") }
                putJsonObject("stream") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("media", "ring", "alarm").forEach { add(it) } }
                }
            }
            putJsonArray("required") { add("percent") }
        },
        permission = ToolPermission.SYSTEM,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val percent = ToolArgs.int(arguments, "percent", -1).coerceIn(0, 100)
        if (percent < 0) return ToolResult.Failure("Missing required argument: percent")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (ToolArgs.str(arguments, "stream")?.lowercase()) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(stream)
        if (max <= 0) return ToolResult.Failure("This stream has no volume control.")
        val target = (percent * max / 100).coerceIn(0, max)
        val ok = runCatching { am.setStreamVolume(stream, target, 0); true }.getOrDefault(false)
        return if (ok) {
            ToolResult.Success(
                "Set ${streamName(stream)} volume to $percent%.",
                buildJsonObject { put("stream", streamName(stream)); put("percent", percent) }
            )
        } else {
            ToolResult.Failure("Could not change the volume.")
        }
    }

    private fun streamName(s: Int) = when (s) {
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_ALARM -> "alarm"
        else -> "media"
    }
}

/**
 * Device information: model, Android version, SDK, screen, RAM, storage.
 */
@Singleton
class DeviceInfoTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_device_info",
        description = "Return device information: model, manufacturer, Android version, screen resolution, RAM and free storage.",
        permission = ToolPermission.DEVICE,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val dm = context.resources.displayMetrics
        val ramBytes = runCatching { Runtime.getRuntime().maxMemory() }.getOrDefault(0L)
        val freeGb = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
        }.getOrDefault(0.0)
        val data = buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            put("dpi", dm.densityDpi)
            put("ram_mb", ramBytes / (1024L * 1024L))
            put("free_storage_gb", freeGb)
        }
        return ToolResult.Success(
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "screen ${dm.widthPixels}x${dm.heightPixels}, RAM ${ramBytes / (1024L * 1024L)} MB, " +
                "${"%.1f".format(freeGb)} GB free",
            data
        )
    }
}

/**
 * Lists installed apps (visible labels + package names). Package visibility
 * is declared in the app manifest `<queries>`, so third-party apps show up.
 */
@Singleton
class PackageManagerTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "list_apps",
        description = "List installed apps (names + package names). Optionally filter by keyword (e.g. 'whats').",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string"); put("description", "Optional filter keyword") }
            }
        },
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = ToolArgs.str(arguments, "query", "filter")?.lowercase()
        // getInstalledApplications is a slow package-manager query — keep it
        // off the main thread (tools run in the chat's scope).
        val pm = context.packageManager
        val apps = withContext(Dispatchers.IO) {
            runCatching {
                pm.getInstalledApplications(0).mapNotNull { app ->
                    val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()
                    if (label == null || label.isBlank()) null else app.packageName to label
                }.sortedBy { it.second.lowercase() }
            }.getOrNull()
        } ?: return ToolResult.Failure("Could not read installed apps.")
        val filtered = if (query != null) apps.filter {
            it.first.contains(query) || it.second.contains(query, ignoreCase = true)
        } else apps
        if (filtered.isEmpty()) {
            return ToolResult.Success("No installed app matches '${query ?: ""}'.")
        }
        val data = buildJsonObject {
            put("count", filtered.size)
            putJsonArray("apps") {
                filtered.forEach { (pkg, label) ->
                    add(buildJsonObject { put("name", label); put("package", pkg) })
                }
            }
        }
        return ToolResult.Success(
            filtered.joinToString("; ", limit = 20, truncated = "…") { (pkg, label) -> "$label ($pkg)" },
            data
        )
    }
}

/**
 * Recently used apps via UsageStatsManager. Needs the "Usage access" special
 * permission; without it the tool reports a clear, actionable failure.
 */
@Singleton
class RunningAppsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_running_apps",
        description = "Return apps used in the last 10 minutes (foreground first). Needs the Usage access permission in system settings.",
        permission = ToolPermission.DEVICE,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
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
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
            return ToolResult.Failure(
                "Usage access is not granted. Open Settings → Apps → AndroLLM → Usage access and enable it, then try again.",
                data = buildJsonObject { put("usage_access", false) }
            )
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, now - 10 * 60 * 1000L, now
        )
        val top = stats.sortedByDescending { it.lastTimeUsed }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .take(10)
        if (top.isEmpty()) return ToolResult.Success("No recent apps found.")
        val pm = context.packageManager
        val data = buildJsonObject {
            putJsonArray("apps") {
                top.forEach { s ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
                    }.getOrDefault(s.packageName)
                    add(buildJsonObject {
                        put("name", label)
                        put("package", s.packageName)
                        put("last_used_minutes_ago", (now - s.lastTimeUsed) / 60000L)
                    })
                }
            }
        }
        return ToolResult.Success(
            top.joinToString(", ") { s ->
                runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
                }.getOrDefault(s.packageName)
            },
            data
        )
    }
}

/**
 * Vibrates the phone for a short burst. VIBRATE is a normal permission —
 * auto-granted at install, no runtime prompt.
 */
@Singleton
class VibrateTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "vibrate",
        description = "Vibrate the phone for a short burst (default ~500ms).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("duration_ms") {
                    put("type", "integer")
                    put("description", "Vibration length in milliseconds (default 500)")
                }
            }
        },
        permission = ToolPermission.SYSTEM,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val duration = ToolArgs.int(arguments, "duration_ms", 500).coerceIn(50, 5000)
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return ToolResult.Failure("This device has no vibrator.")
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration.toLong())
            }
            true
        }.getOrDefault(false)
        return if (ok) {
            ToolResult.Success(
                "Vibrated for ${duration}ms.",
                buildJsonObject { put("duration_ms", duration) }
            )
        } else {
            ToolResult.Failure("Could not vibrate the device.")
        }
    }
}

/**
 * Sets the screen brightness (0–100). Requires the special
 * 'Modify system settings' permission (WRITE_SETTINGS) — the tool fails with
 * a clear message when it is not granted instead of silently doing nothing.
 */
@Singleton
class BrightnessTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_brightness",
        description = "Set the screen brightness to a percentage (0–100). Requires the special 'Modify system settings' permission.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("percent") { put("type", "integer"); put("description", "0–100") }
            }
            putJsonArray("required") { add("percent") }
        },
        permission = ToolPermission.SYSTEM,
        requiresConfirmation = true,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val percent = ToolArgs.int(arguments, "percent", -1).coerceIn(0, 100)
        if (percent < 0) return ToolResult.Failure("Missing required argument: percent")
        if (!Settings.System.canWrite(context)) {
            return ToolResult.Failure(
                "Screen brightness needs the 'Modify system settings' permission. " +
                    "Open Settings → Apps → AndroLLM → Modify system settings and enable it, then try again."
            )
        }
        val ok = runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (percent * 255 / 100).coerceIn(1, 255)
            )
        }.getOrDefault(false)
        return if (ok) {
            ToolResult.Success(
                "Set screen brightness to $percent%.",
                buildJsonObject { put("percent", percent) }
            )
        } else {
            ToolResult.Failure("Could not change the screen brightness.")
        }
    }
}

/**
 * Opens the system clock / alarms app (no permission needed).
 */
@Singleton
class ClockTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "open_clock",
        description = "Open the system clock and alarms app.",
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val showAlarms = Intent("android.intent.action.SHOW_ALARMS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching {
            if (showAlarms.resolveActivity(context.packageManager) != null) {
                context.startActivity(showAlarms)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory("android.intent.category.APP_CLOCK")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            true
        }.getOrDefault(false)
        return if (ok) {
            ToolResult.Success("Opened the clock app.")
        } else {
            ToolResult.Failure("Could not open the clock app.")
        }
    }
}

/**
 * Reads the device's last known location (GPS or network). The confirmation
 * card requests the location permission on approve; without it the tool
 * fails with a clear message.
 */
@Singleton
class LocationTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "get_location",
        description = "Read the device's last known location (latitude, longitude, accuracy). Requires location permission.",
        permission = ToolPermission.LOCATION,
        requiresConfirmation = true,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolResult.Failure("Location services are unavailable on this device.")
        val fine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            return ToolResult.Failure(
                "Location permission is not granted — enable it in Settings → Permissions, then try again."
            )
        }
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return ToolResult.Failure(
                "No recent location is available — open any maps app once to warm up the last-known location, then try again."
            )
        val data = buildJsonObject {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_m", location.accuracy.toDouble())
        }
        return ToolResult.Success(
            "Last known location: ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)} (±${location.accuracy}m)",
            data
        )
    }
}
