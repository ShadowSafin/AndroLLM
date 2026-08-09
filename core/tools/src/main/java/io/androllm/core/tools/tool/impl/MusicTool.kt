package io.androllm.core.tools.tool.impl

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.view.KeyEvent
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
 * Media playback control (play / pause / next / previous).
 *
 * Two paths, tried in order:
 * 1. **Active media sessions** — reliable on older Android and for the app's
 *    own session. On modern Android enumerating sessions requires the
 *    signature-level `MEDIA_CONTENT_CONTROL` permission and throws
 *    `SecurityException`.
 * 2. **Global media-key dispatch** (`AudioManager.dispatchMediaKeyEvent`) —
 *    the standard permission-free path; the registered media-button receiver
 *    (usually the last music app) handles it.
 */
@Singleton
class MusicTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "control_music",
        description = "Control the active music or media player: play, pause, next or previous track.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("play", "pause", "next", "previous").forEach { add(it) }
                    }
                }
            }
            putJsonArray("required") { add("action") }
        },
        permission = ToolPermission.MUSIC,
        category = ToolCategory.MEDIA
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = ToolArgs.str(arguments, "action")?.lowercase() ?: "play"
        if (action !in setOf("play", "pause", "next", "previous")) {
            return ToolResult.Failure("Unknown music action '$action' (use play, pause, next or previous).")
        }
        // Preferred path: control the active media session directly. When the
        // OS blocks session enumeration (modern Android) we fall through to
        // the media-key dispatch below.
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = msm.getActiveSessions(null)
            if (controllers.isEmpty()) {
                return ToolResult.Failure("No music player is currently active.")
            }
            var controlled = false
            for (controller in controllers) {
                val controls = controller.transportControls ?: continue
                when (action) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "next" -> controls.skipToNext()
                    "previous" -> controls.skipToPrevious()
                }
                controlled = true
            }
            if (controlled) {
                return ToolResult.Success(
                    summary = "Music: $action.",
                    data = buildJsonObject {
                        put("action", action)
                        put("status", action)
                    }
                )
            }
        } catch (t: SecurityException) {
            Timber.d(t, "MusicTool: session control blocked — using media-key dispatch")
        }

        // Fallback: dispatch the global media key. Never crashes; reports
        // honestly what it did.
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val key = actionToKey(action)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            ToolResult.Success(
                summary = "Sent the $action command to your music app.",
                data = buildJsonObject {
                    put("action", action)
                    put("status", "dispatched")
                }
            )
        }.getOrElse {
            ToolResult.Failure(
                "This Android version restricts music control — play it in your music app. (${it.message ?: it.javaClass.simpleName})"
            )
        }
    }

    private fun actionToKey(action: String): Int = when (action) {
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
    }
}
