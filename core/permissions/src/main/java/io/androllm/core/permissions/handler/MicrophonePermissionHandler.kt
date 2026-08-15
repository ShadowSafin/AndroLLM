package io.androllm.core.permissions.handler

import android.Manifest
import android.content.Context
import android.os.Build
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.PermissionState
import io.androllm.core.utils.PermissionUtils
import javax.inject.Inject

/**
 * Voice Assistant — wake word, voice input and hands-free replies.
 *
 * Requests the microphone (plus notifications on Android 13+ so the
 * always-on service can surface status) through one system dialog. The
 * foreground-microphone service permission is a manifest declaration, not a
 * runtime gate, so it is never requested here.
 */
class MicrophonePermissionHandler @Inject constructor() : PermissionHandler {

    override val id = "voice_assistant"
    override val title = "Voice Assistant"
    override val description = "Talk to AndroLLM hands-free and use voice features."
    override val explanation = "The microphone powers the “Hey Andro” wake word, spoken " +
        "questions and voice replies. Audio is processed on your device and never uploaded."
    override val isRequired = false
    override val isOptional = true
    override val needsSettingsScreen = false

    override fun runtimePermissions(context: Context): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun isFeatureEnabled(context: Context): Boolean = true

    override fun rawState(context: Context): PermissionState =
        if (PermissionUtils.hasRecordAudioPermission(context)) PermissionState.GRANTED
        else PermissionState.DENIED

    override fun openSettings(context: Context): Boolean = PermissionIntents.appDetails(context)
}
