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
import timber.log.Timber

/**
 * Launches an installed app by display label or package name ("open Discord",
 * "open Spotify"). Resolution goes through the [AppIndex] built from
 * PackageManager with fuzzy matching ("YT" → YouTube, "insta" → Instagram):
 *
 * 1. Exact package name, then exact/partial/fuzzy label match.
 * 2. Multiple close matches → the tool lists the candidates and asks instead
 *    of guessing (e.g. Discord vs Discord Canary).
 * 3. Installed but no launcher activity → explains why, never claims "not
 *    installed".
 * 4. Not found at all → suggests a Play Store search.
 */
@Singleton
class AppLauncherTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appIndex: AppIndex
) : Tool {

    override val spec = ToolSpec(
        name = "launch_app",
        description = "Launch an installed app by its name (e.g. 'Spotify', 'Settings', 'WhatsApp') or package name. Matches partial and fuzzy names ('YT' for YouTube).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("app") {
                    put("type", "string")
                    put("description", "App name or package name")
                }
            }
            putJsonArray("required") { add("app") }
        },
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val app = ToolArgs.str(arguments, "app", "name", "package")
            ?: return ToolResult.Failure("Missing required argument: app")

        // "settings" is a super-common request with no launcher activity.
        if (app.equals("settings", ignoreCase = true)) {
            val opened = ToolIntents.launch(context, Intent(android.provider.Settings.ACTION_SETTINGS))
            return if (opened) ToolResult.Success("Opened Settings.")
            else ToolResult.Failure("Could not open Settings.")
        }

        val entries = appIndex.all()
        if (entries.isEmpty()) {
            return ToolResult.Failure("I could not read the list of installed apps on this device.")
        }

        val result = AppSearch.search(entries, app)
        Timber.i("AppLauncherTool: '%s' → %d match(es), ambiguous=%s", app, result.matches.size, result.ambiguous)

        if (result.matches.isEmpty()) {
            // STEP 9 — never falsely claim it isn't installed; offer the Play Store.
            return ToolResult.Failure(
                "I couldn't find \"$app\" installed on this device. Would you like me to search the Play Store for it?",
                data = buildJsonObject {
                    put("app", app)
                    put("status", "not_found")
                }
            )
        }

        if (result.ambiguous) {
            val candidates = result.matches.joinToString("; ") {
                "\"${it.label}\" (${it.packageName})"
            }
            return ToolResult.Failure(
                "Several apps match \"$app\": $candidates. Which one do you mean?",
                data = buildJsonObject {
                    put("app", app)
                    put("status", "ambiguous")
                    putJsonArray("candidates") { result.matches.forEach { add(it.packageName) } }
                }
            )
        }

        val entry = result.matches.first()
        val pm = context.packageManager
        // STEP 3 — verify the launch intent; never claim missing on null.
        val intent = entry.launchIntent
            ?: runCatching { pm.getLaunchIntentForPackage(entry.packageName) }.getOrNull()
        if (intent == null) {
            return ToolResult.Failure(
                "\"${entry.label}\" is installed but has no launcher activity — it may be a settings pane or widget-only app, so I can't open it directly.",
                data = buildJsonObject {
                    put("app", app)
                    put("package", entry.packageName)
                    put("status", "no_launcher")
                }
            )
        }

        val launched = ToolIntents.launch(context, intent)
        return if (launched) {
            ToolResult.Success(
                summary = "Opened ${entry.label}.",
                data = buildJsonObject {
                    put("app", app)
                    put("label", entry.label)
                    put("package", entry.packageName)
                    put("status", "launched")
                }
            )
        } else {
            ToolResult.Failure(
                "Could not open \"${entry.label}\" — the system blocked the launch. Try opening it from the app drawer.",
                data = buildJsonObject {
                    put("app", app)
                    put("package", entry.packageName)
                    put("status", "launch_blocked")
                }
            )
        }
    }
}
