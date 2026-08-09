package io.androllm.core.tools.tool.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Index of every installed app (label, package, launcher intent, category),
 * built from PackageManager — never hardcoded. Cached until [refresh] (app
 * installs/uninstalls) and rebuilt lazily on first use.
 *
 * Apps that expose no LAUNCHER activity are still listed ([AppEntry.hasLauncher]
 * = false) so the tool can explain *why* an app can't be opened instead of
 * claiming it isn't installed.
 */
@Singleton
class AppIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile private var cache: List<AppEntry>? = null

    /** All installed apps, newest index first. Builds the index on first use. */
    fun all(): List<AppEntry> {
        cache?.let { return it }
        return synchronized(this) {
            cache?.let { return it }
            build().also { cache = it }
        }
    }

    /** Drops the cache (called after app installs/uninstalls if ever needed). */
    fun refresh() {
        synchronized(this) { cache = null }
    }

    private fun build(): List<AppEntry> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // One launcher query instead of N getLaunchIntentForPackage() calls.
        val launchers = runCatching {
            pm.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { ri ->
                    val info = ri.activityInfo ?: return@mapNotNull null
                    info.packageName to Intent(launcherIntent).apply {
                        setClassName(info.packageName, info.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                .toMap()
        }.getOrDefault(emptyMap())

        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val entries = apps.mapNotNull { app ->
            val pkg = app.packageName
            val label = runCatching { pm.getApplicationLabel(app).toString() }
                .getOrNull()?.trim() ?: return@mapNotNull null
            if (label.isEmpty()) return@mapNotNull null
            AppEntry(
                label = label,
                packageName = pkg,
                hasLauncher = launchers.containsKey(pkg),
                launchIntent = launchers[pkg],
                category = categorize(label, pkg)
            )
        }.sortedBy { it.label.lowercase() }

        Timber.i("AppIndex: indexed ${entries.size} apps (${launchers.size} launcher activities)")
        return entries
    }

    private fun categorize(label: String, pkg: String): String {
        val l = label.lowercase()
        val p = pkg.lowercase()
        return when {
            l.contains("settings") || l.contains("launcher") || p.contains("settings") ||
                p.startsWith("com.android.") -> "System"
            SOCIAL.any { l.contains(it) || p.contains(it) } -> "Social"
            MEDIA.any { l.contains(it) || p.contains(it) } -> "Media"
            TOOLS.any { l.contains(it) || p.contains(it) } -> "Tools"
            l.contains("game") || p.contains(".game") || p.contains("supercell") -> "Games"
            else -> "Other"
        }
    }

    private companion object {
        val SOCIAL = listOf("whatsapp", "discord", "instagram", "telegram", "facebook", "messenger", "twitter", "snapchat", "signal", "linkedin", "reddit", "x ")
        val MEDIA = listOf("youtube", "spotify", "netflix", "prime", "music", "video", "movie", "tv", "hotstar", "amazon", "photos", "camera", "gallery", "picsart")
        val TOOLS = listOf("chrome", "browser", "file", "calculator", "clock", "calendar", "gmail", "mail", "maps", "note", "drive", "doc", "pdf", "translate", "weather", "bank", "phone", "contact")
    }
}
